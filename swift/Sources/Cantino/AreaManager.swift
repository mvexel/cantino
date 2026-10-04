// The area download lifecycle on Apple platforms: the Swift counterpart of
// the Kotlin AreaManager + AreaDownloadWorker, with WorkManager replaced by
// in-process tasks (decided 2026-10-04: downloads run while the app runs;
// no background URLSession).
//
// What WorkManager did, and where it lives here:
// - Unique work per area, REPLACE: ``Registry`` keeps one current run per
//   area; a new download cancels the old run and waits for it to unwind.
// - Durable request, re-run after process death: the request is written to
//   `<downloads>/<areaId>/request.json` before `download` returns; a new
//   ``AreaManager`` resumes every request it finds (same run ID, so the
//   SliceOSM job checkpoint is reused and a run that died after its commit
//   point finds its area published and succeeds at once).
// - Retry with backoff: ``DownloadRun`` makes up to
//   ``DownloadTuning/maxRunAttempts`` attempts with exponential backoff
//   (state ``AreaState/queued(runId:previousRuns:)`` in between).
// - Work state and progress: ``Registry`` (memory only; after a restart a
//   finished run reads as idle with the published area).
//
// Publication, recovery and the area lock are the core's (``AreaStorage``),
// shared with Android; failure classification is the core's too.

import Foundation

/// Downloads, refreshes and locates the offline areas of an app.
///
/// Model: an app names each area with an ID: 1 to 64 characters from
/// `[A-Za-z0-9_-]` (e.g. `"city"`); IDs become file names, and every method
/// throws ``CantinoError/invalidArgument(_:)`` for any other ID. Each ID has
/// at most one published area: OSM data at `<directory>/cantino-areas/<areaId>.sqlite`
/// and, when requested with a ``BasemapSource``, a PMTiles basemap next to
/// it. A ``download(areaId:bbox:name:basemap:)`` fetches a fresh SliceOSM
/// extract for a bbox (and the basemap) and, only once every part is ready,
/// replaces the published area in one commit. A refresh is a full
/// re-download.
///
/// **Downloads run in the app's process.** They continue while the app is
/// in the foreground (and for the short time iOS keeps a backgrounded app
/// running). If the app is suspended or killed, the run stops; the next
/// ``AreaManager`` created for the same directory (typically at the next
/// launch) resumes it with the same run ID, reusing the SliceOSM job it had
/// submitted. Transient failures retry with backoff.
///
/// Guarantees for callers (as on Android):
/// - Until a download reaches ``AreaState/ready(runId:area:)``, the
///   previously published area is untouched: failure, cancellation and the
///   app being killed never damage or remove it.
/// - Data, basemap and metadata are replaced together: a reader sees either
///   the complete old area or the complete new one. An open store keeps
///   reading its old snapshot; reopen it after ``AreaState/ready(runId:area:)``.
///
/// Threading: every method may be called from any thread or task.
/// ``download(areaId:bbox:name:basemap:)`` and ``cancel(areaId:)`` write or
/// delete one small file; ``publishedArea(areaId:)`` reads the disk (and may
/// wait a few milliseconds for a commit in progress). Instances are
/// lightweight; all instances for the same directory share their runs and
/// states. All of an app's area access must happen in one process.
///
/// Storage: `<directory>/cantino-areas` holds the published areas (included
/// in backups unless the app excludes it); `<directory>/cantino-area-downloads`
/// holds requests and partial downloads and is excluded from backup.
public final class AreaManager: Sendable {
    private let registry: Registry

    /// The default parent directory: Application Support.
    public static var defaultDirectory: URL {
        FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
    }

    /// An area manager for `directory` (default: Application Support) with
    /// `config` for the downloads it starts. Creating the first manager for
    /// a directory in a process resumes the downloads that were interrupted
    /// when the app last stopped.
    public convenience init(directory: URL = AreaManager.defaultDirectory, config: AreaConfig = AreaConfig()) {
        self.init(directory: directory, config: config, sessionConfiguration: .ephemeral)
    }

    /// `sessionConfiguration` lets tests route HTTP to an in-process server.
    init(directory: URL, config: AreaConfig, sessionConfiguration: URLSessionConfiguration) {
        registry = Registry.shared(directory: directory, sessionConfiguration: sessionConfiguration)
        self.config = config
        registry.resumeInterrupted()
    }

    private let config: AreaConfig

    /// Starts downloading `bbox` as area `areaId` and returns the ID of the
    /// new run (the same ID ends up in ``AreaMetadata/workId`` once it
    /// publishes).
    ///
    /// A download already running for the same ID is cancelled and replaced
    /// (its partial data is discarded; the published area is unaffected).
    /// `name` is the job name shown by SliceOSM. `basemap` opts into an
    /// offline basemap for the same bbox (default: none; a refresh without
    /// one removes a previous basemap).
    ///
    /// An invalid `bbox` is not rejected here but fails the run with a
    /// non-retryable ``FailureReason/invalidRequest``. Throws
    /// ``CantinoError/invalidArgument(_:)`` for an invalid `areaId`, and
    /// ``CantinoError/io(_:)`` if the request cannot be stored.
    @discardableResult
    public func download(areaId: String, bbox: Bbox, name: String? = nil, basemap: BasemapSource = .none) throws -> UUID {
        try AreaStorage.validate(areaId: areaId)
        let request = DownloadRequest(workId: UUID(), areaId: areaId, bbox: bbox, name: name ?? areaId,
                                      basemap: basemap, config: config)
        try registry.start(request)
        return request.workId
    }

    /// Cancels a running download of `areaId`. The published area stays.
    /// No-op if none. A run already publishing (a few milliseconds of
    /// renames) finishes and is reported as ``AreaState/ready(runId:area:)``.
    /// Returns immediately; the state turns cancelled shortly after.
    public func cancel(areaId: String) throws {
        try AreaStorage.validate(areaId: areaId)
        registry.cancel(areaId: areaId)
    }

    /// Download state of `areaId`: the current state first, then every
    /// change (consecutive duplicates dropped). It never finishes on its
    /// own; stop iterating (or cancel the iterating task) to stop.
    ///
    /// The stream follows the *area*, not one run: right after a download
    /// it can still yield the previous run's final state. Match
    /// ``AreaState/runId`` to wait for your own download:
    ///
    /// ```swift
    /// let runId = try areaManager.download(areaId: "home", bbox: bbox)
    /// for await state in try areaManager.state(areaId: "home")
    ///     where state.runId == runId && state.isTerminal {
    ///     handle(state)
    ///     break
    /// }
    /// ```
    public func state(areaId: String) throws -> AsyncStream<AreaState> {
        try AreaStorage.validate(areaId: areaId)
        return registry.subscribe(areaId: areaId)
    }

    /// The published area of `areaId` with its files and metadata, or nil if
    /// none is published. Reads the disk: call it off the main thread (or
    /// use ``loadPublishedArea(areaId:)``). Throws ``CantinoError/io(_:)`` if
    /// a pending publication cannot be recovered; no incomplete area is
    /// returned.
    public func publishedArea(areaId: String) throws -> AreaInfo? {
        try AreaStorage.validate(areaId: areaId)
        return try registry.storage.published(areaId: areaId)
    }

    /// ``publishedArea(areaId:)`` without blocking the calling task's thread.
    public func loadPublishedArea(areaId: String) async throws -> AreaInfo? {
        try AreaStorage.validate(areaId: areaId)
        let storage = registry.storage
        return try await blocking { try storage.published(areaId: areaId) }
    }
}

/// Test-only hooks into the download path (nil in production): blocking code
/// at points where a real device would be slow, so tests can cancel there.
enum AreaTestHooks {
    private static let lock = NSLock()
    nonisolated(unsafe) private static var _afterImport: (@Sendable () -> Void)?
    nonisolated(unsafe) private static var _afterCommitPoint: (@Sendable () -> Void)?

    /// Runs right after the (uninterruptible) import returned.
    static var afterImport: (@Sendable () -> Void)? {
        get { lock.withLock { _afterImport } }
        set { lock.withLock { _afterImport = newValue } }
    }

    /// Runs inside the commit, right after the commit point (under the area lock).
    static var afterCommitPoint: (@Sendable () -> Void)? {
        get { lock.withLock { _afterCommitPoint } }
        set { lock.withLock { _afterCommitPoint = newValue } }
    }
}

/// Runs blocking work (import, disk, the area lock) off the cooperative
/// thread pool.
func blocking<T: Sendable>(_ body: @escaping @Sendable () throws -> T) async throws -> T {
    try await withCheckedThrowingContinuation { continuation in
        DispatchQueue.global(qos: .utility).async { continuation.resume(with: Result { try body() }) }
    }
}

// MARK: - Requests on disk

/// Everything a run needs, stored as `request.json` so a resumed run has it.
struct DownloadRequest: Sendable {
    let workId: UUID
    let areaId: String
    let bbox: Bbox
    let name: String
    let basemap: BasemapSource
    let config: AreaConfig

    var json: JSONValue {
        let basemapJSON: JSONValue = switch basemap.kind {
        case .none: .null
        case .url(let url): .object(["kind": .string("url"), "url": .string(url)])
        case .extract(let url, let maxZoom, let overfetch):
            .object(["kind": .string("extract"), "url": .string(url), "max_zoom": .int(Int64(maxZoom)),
                     "overfetch": .double(overfetch)])
        }
        return .object([
            "work_id": .string(workId.uuidString.lowercased()),
            "area_id": .string(areaId),
            "bbox": .object(["west": .double(bbox.west), "south": .double(bbox.south),
                             "east": .double(bbox.east), "north": .double(bbox.north)]),
            "name": .string(name),
            "basemap": basemapJSON,
            "slice_base_url": .string(config.sliceBaseUrl),
            "timeout": .double(config.timeout),
            "import_options": config.importOptions.jsonValue,
        ])
    }

    static func parse(_ data: Data) -> DownloadRequest? {
        guard let stored = try? JSONDecoder().decode(StoredRequest.self, from: data),
              let workId = UUID(uuidString: stored.workId) else { return nil }
        let basemap: BasemapSource
        switch stored.basemap?.kind {
        case nil: basemap = .none
        case "url": guard let source = try? BasemapSource.url(stored.basemap!.url) else { return nil }
            basemap = source
        case "extract":
            guard let source = try? BasemapSource.extract(planetUrl: stored.basemap!.url,
                                                          maxZoom: stored.basemap!.maxZoom ?? 15,
                                                          overfetch: stored.basemap!.overfetch ?? 0.05) else { return nil }
            basemap = source
        default: return nil
        }
        let options = stored.importOptions
        return DownloadRequest(
            workId: workId, areaId: stored.areaId,
            bbox: Bbox(west: stored.bbox.west, south: stored.bbox.south, east: stored.bbox.east, north: stored.bbox.north),
            name: stored.name, basemap: basemap,
            config: AreaConfig(sliceBaseUrl: stored.sliceBaseUrl, timeout: stored.timeout,
                               importOptions: ImportOptions(preserveUntaggedMetadata: options.preserveUntaggedMetadata,
                                                            cacheMiB: options.cacheMb,
                                                            profile: options.profile?.model)))
    }

    private struct StoredRequest: Decodable {
        struct Box: Decodable { let west, south, east, north: Double }
        struct Basemap: Decodable {
            let kind: String
            let url: String
            let maxZoom: Int?
            let overfetch: Double?
            enum CodingKeys: String, CodingKey { case kind, url, overfetch; case maxZoom = "max_zoom" }
        }
        struct Options: Decodable {
            let preserveUntaggedMetadata: Bool
            let cacheMb: Int
            let profile: WireProfile?
            enum CodingKeys: String, CodingKey {
                case profile
                case preserveUntaggedMetadata = "preserve_untagged_metadata"
                case cacheMb = "cache_mb"
            }
        }
        let workId: String
        let areaId: String
        let bbox: Box
        let name: String
        let basemap: Basemap?
        let sliceBaseUrl: String
        let timeout: Double
        let importOptions: Options
        enum CodingKeys: String, CodingKey {
            case bbox, name, basemap, timeout
            case workId = "work_id"
            case areaId = "area_id"
            case sliceBaseUrl = "slice_base_url"
            case importOptions = "import_options"
        }
    }
}

/// The per-area download scratch directory: request, SliceOSM job
/// checkpoint, the PBF and basemap ranges in flight.
///
/// ```
/// <downloads>/<areaId>/request.json            the request of the current run
/// <downloads>/<areaId>/checkpoint.json         SliceOSM job of the current run
/// <downloads>/<areaId>/<workId>.osm.pbf.part   PBF being downloaded
/// <downloads>/<areaId>/range-<id>.part         basemap tile range in flight
/// ```
struct DownloadFiles: Sendable {
    let root: URL

    func directory(_ areaId: String) -> URL { root.appendingPathComponent(areaId, isDirectory: true) }
    func request(_ areaId: String) -> URL { directory(areaId).appendingPathComponent("request.json") }
    func checkpoint(_ areaId: String) -> URL { directory(areaId).appendingPathComponent("checkpoint.json") }
    func pbf(_ areaId: String, _ workId: UUID) -> URL {
        directory(areaId).appendingPathComponent("\(workId.uuidString.lowercased()).osm.pbf.part")
    }

    /// Creates the directories, excluded from backup.
    func prepare(_ areaId: String) throws {
        let files = FileManager.default
        try files.createDirectory(at: directory(areaId), withIntermediateDirectories: true)
        var root = self.root
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        try? root.setResourceValues(values)
    }

    func writeRequest(_ request: DownloadRequest) throws {
        try prepare(request.areaId)
        try writeAtomically(request.json.canonicalString, to: self.request(request.areaId))
    }

    /// Deletes the request if it is `workId`'s (a replacing run may have
    /// written its own already).
    func clearRequest(_ areaId: String, workId: UUID?) {
        let file = request(areaId)
        if let workId {
            guard let data = try? Data(contentsOf: file), DownloadRequest.parse(data)?.workId == workId else { return }
        }
        try? FileManager.default.removeItem(at: file)
    }

    /// Every stored request (resumed at startup).
    func storedRequests() -> [DownloadRequest] {
        guard let areas = try? FileManager.default.contentsOfDirectory(at: root, includingPropertiesForKeys: nil) else {
            return []
        }
        return areas.compactMap { area in
            (try? Data(contentsOf: area.appendingPathComponent("request.json"))).flatMap(DownloadRequest.parse)
        }
    }

    /// The job ID checkpointed by run `workId` against `base`, if any.
    func readCheckpoint(_ areaId: String, workId: UUID, base: String) -> String? {
        guard let data = try? Data(contentsOf: checkpoint(areaId)),
              let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              json["work_id"] as? String == workId.uuidString.lowercased(), json["base"] as? String == base else {
            return nil
        }
        return json["job_id"] as? String
    }

    func writeCheckpoint(_ areaId: String, workId: UUID, base: String, jobId: String) throws {
        let json = JSONValue.object(["work_id": .string(workId.uuidString.lowercased()), "base": .string(base),
                                     "job_id": .string(jobId)])
        try writeAtomically(json.canonicalString, to: checkpoint(areaId))
    }

    /// Deletes the checkpoint if it is `workId`'s.
    func clearCheckpoint(_ areaId: String, workId: UUID) {
        let file = checkpoint(areaId)
        if let data = try? Data(contentsOf: file),
           let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
           let owner = json["work_id"] as? String, owner != workId.uuidString.lowercased() {
            return
        }
        try? FileManager.default.removeItem(at: file)
    }

    /// Deletes partial downloads of other runs (killed runs never clean up).
    func deleteStale(_ areaId: String, keep: URL) {
        let files = (try? FileManager.default.contentsOfDirectory(at: directory(areaId), includingPropertiesForKeys: nil)) ?? []
        for file in files where file.pathExtension == "part" && file.lastPathComponent != keep.lastPathComponent {
            try? FileManager.default.removeItem(at: file)
        }
    }

    private func writeAtomically(_ text: String, to target: URL) throws {
        try Data(text.utf8).write(to: target, options: .atomic)
    }
}

// MARK: - Registry (what WorkManager held)

/// The runs and states of one directory, shared by every ``AreaManager`` for
/// it in this process. All mutable state is guarded by `lock`; subscribers
/// are yielded to under it, so states arrive in order.
final class Registry: @unchecked Sendable {
    let storage: AreaStorage
    let files: DownloadFiles
    /// Copied for each run's session (tests route it to a fake server).
    private let sessionConfiguration: URLSessionConfiguration
    private let lock = NSLock()
    private var areas: [String: Entry] = [:]
    private var resumed = false

    private struct Entry {
        var run: DownloadRun?
        var last: AreaState?
        var subscribers: [UUID: AsyncStream<AreaState>.Continuation] = [:]
    }

    private static let instancesLock = NSLock()
    nonisolated(unsafe) private static var instances: [String: Registry] = [:]

    static func shared(directory: URL, sessionConfiguration: URLSessionConfiguration) -> Registry {
        instancesLock.withLock {
            let key = directory.standardizedFileURL.path
            if let registry = instances[key] { return registry }
            let registry = Registry(directory: directory, sessionConfiguration: sessionConfiguration)
            instances[key] = registry
            return registry
        }
    }

    /// Forgets every registry (tests: a "restarted process").
    static func resetForTesting() {
        instancesLock.withLock { instances = [:] }
    }

    private init(directory: URL, sessionConfiguration: URLSessionConfiguration) {
        storage = AreaStorage(root: directory.appendingPathComponent("cantino-areas").path)
        files = DownloadFiles(root: directory.appendingPathComponent("cantino-area-downloads", isDirectory: true))
        self.sessionConfiguration = sessionConfiguration
    }

    /// An HTTP client for one run, with the run's timeout.
    func http(timeout: TimeInterval) -> AreaHTTP {
        AreaHTTP(configuration: sessionConfiguration.copy() as! URLSessionConfiguration, timeout: timeout)
    }

    /// Starts the stored requests of runs interrupted by the app stopping
    /// (once per directory and process).
    func resumeInterrupted() {
        let start = lock.withLock { () -> Bool in
            defer { resumed = true }
            return !resumed
        }
        guard start else { return }
        for request in files.storedRequests() {
            launch(request)
        }
    }

    /// Stores `request` and starts it, replacing the area's current run.
    func start(_ request: DownloadRequest) throws {
        do {
            try files.writeRequest(request)
        } catch {
            throw CantinoError.io("cannot store the download request: \(error.localizedDescription)")
        }
        launch(request)
    }

    private func launch(_ request: DownloadRequest) {
        let run = DownloadRun(request: request, registry: self)
        let previous = lock.withLock { () -> DownloadRun? in
            var entry = areas[request.areaId] ?? Entry()
            let previous = entry.run
            entry.run = run
            areas[request.areaId] = entry
            return previous
        }
        previous?.cancel(replaced: true)
        emit(.submitting(runId: request.workId), areaId: request.areaId, run: request.workId)
        run.start(after: previous)
    }

    func cancel(areaId: String) {
        let run = lock.withLock { areas[areaId]?.run }
        guard let run else { return }
        // The request goes first, so a kill right after cancel resumes nothing.
        if run.cancel(replaced: false) {
            files.clearRequest(areaId, workId: run.request.workId)
        }
    }

    /// Records and broadcasts `state` if `run` is still the area's current
    /// run (a replaced run's late reports are dropped).
    func emit(_ state: AreaState, areaId: String, run: UUID) {
        lock.withLock {
            guard var entry = areas[areaId], entry.run?.request.workId == run else { return }
            guard entry.last != state else { return }
            entry.last = state
            areas[areaId] = entry
            for subscriber in entry.subscribers.values { subscriber.yield(state) }
        }
    }

    /// A run ended: it stays the area's last run (its state is final) but
    /// is no longer running.
    func finished(areaId: String, run: UUID) {
        files.clearRequest(areaId, workId: run)
    }

    func subscribe(areaId: String) -> AsyncStream<AreaState> {
        let id = UUID()
        let (stream, continuation) = AsyncStream<AreaState>.makeStream(bufferingPolicy: .unbounded)
        continuation.onTermination = { [weak self] _ in
            self?.lock.withLock { _ = self?.areas[areaId]?.subscribers.removeValue(forKey: id) }
        }
        let last = lock.withLock { () -> AreaState? in
            var entry = areas[areaId] ?? Entry()
            entry.subscribers[id] = continuation
            areas[areaId] = entry
            if let last = entry.last { continuation.yield(last) }
            return entry.last
        }
        if last == nil {
            // No run known: the area on disk. Read off the caller's thread.
            let storage = self.storage
            Task.detached { [weak self] in
                let published = try? await blocking { try storage.published(areaId: areaId) }
                self?.lock.withLock {
                    guard let self, var entry = self.areas[areaId], entry.last == nil else { return }
                    let state = AreaState.idle(published: published)
                    entry.last = state
                    self.areas[areaId] = entry
                    for subscriber in entry.subscribers.values { subscriber.yield(state) }
                }
            }
        }
        return stream
    }
}

// MARK: - One run (what the worker did)

/// Cancellation of one run, decided under a lock so that the check right
/// before the commit point and a concurrent cancel agree: once the run has
/// entered its commit, cancel is ignored (the run publishes and reports
/// ready); before that, the commit refuses.
final class RunToken: @unchecked Sendable {
    private let lock = NSLock()
    private var cancelled = false
    private(set) var replaced = false
    private var committing = false

    /// Marks the run cancelled; false if it is already committing.
    func cancel(replaced: Bool) -> Bool {
        lock.withLock {
            if committing { return false }
            cancelled = true
            self.replaced = self.replaced || replaced
            return true
        }
    }

    /// The last check before the commit point (runs under the area lock).
    func enterCommit() throws {
        try lock.withLock {
            if cancelled { throw CancellationError() }
            committing = true
        }
    }

    var isReplaced: Bool { lock.withLock { replaced } }
}

final class DownloadRun: @unchecked Sendable {
    let request: DownloadRequest
    private unowned let registry: Registry
    private let token = RunToken()
    private let lock = NSLock()
    private var task: Task<Void, Never>?
    private var lastProgress = Date.distantPast
    private var lastPhase = ""

    private let http: AreaHTTP

    init(request: DownloadRequest, registry: Registry) {
        self.request = request
        self.registry = registry
        http = registry.http(timeout: request.config.timeout)
    }

    private var id: UUID { request.workId }
    private var areaId: String { request.areaId }

    /// Starts the run once `previous` (a replaced run) has unwound.
    func start(after previous: DownloadRun?) {
        let task = Task.detached { [self] in
            await previous?.task?.value
            await self.execute()
        }
        lock.withLock { self.task = task }
    }

    /// Cancels the run; false if it is already committing (it will publish).
    @discardableResult
    func cancel(replaced: Bool) -> Bool {
        guard token.cancel(replaced: replaced) else { return false }
        lock.withLock { task }?.cancel()
        return true
    }

    private func report(_ state: AreaState) {
        registry.emit(state, areaId: areaId, run: id)
    }

    /// Progress at most every 250 ms per phase (phase changes always pass).
    private func progress(_ phase: String, _ state: AreaState) {
        let now = Date()
        let pass = lock.withLock { () -> Bool in
            guard phase != lastPhase || now.timeIntervalSince(lastProgress) >= 0.25 else { return false }
            lastPhase = phase
            lastProgress = now
            return true
        }
        if pass { report(state) }
    }

    // MARK: Attempts

    private func execute() async {
        let tuning = DownloadTuning.current
        var attempt = 0
        while true {
            let outcome = await runAttempt()
            switch outcome {
            case .published(let area):
                registry.files.clearCheckpoint(areaId, workId: id)
                report(.ready(runId: id, area: area))
            case .cancelled:
                if !token.isReplaced { report(.cancelled(runId: id)) }
            case .failed(let failure):
                switch failure.kind {
                case .permanent:
                    registry.files.clearCheckpoint(areaId, workId: id)
                    report(.failed(runId: id, message: failure.message, retryable: false, reason: failure.reason))
                case .transient, .storage, .jobGone:
                    // A job whose file vanished must not be resumed.
                    if failure.kind == .jobGone { registry.files.clearCheckpoint(areaId, workId: id) }
                    attempt += 1
                    if attempt < tuning.maxRunAttempts {
                        report(.queued(runId: id, previousRuns: attempt))
                        let delay = tuning.backoffDelay * pow(2, Double(attempt - 1))
                        do {
                            try await Task.sleep(nanoseconds: UInt64(delay * 1e9))
                            continue
                        } catch {
                            if !token.isReplaced { report(.cancelled(runId: id)) }
                            break
                        }
                    }
                    registry.files.clearCheckpoint(areaId, workId: id)
                    report(.failed(runId: id, message: failure.message, retryable: true, reason: failure.reason))
                }
            }
            if !token.isReplaced { registry.finished(areaId: areaId, run: id) }
            return
        }
    }

    private enum Outcome {
        case published(AreaInfo)
        case cancelled
        case failed(DownloadFailure)
    }

    private func runAttempt() async -> Outcome {
        let files = registry.files
        let staging = files.pbf(areaId, id)
        do {
            defer {
                // Also on cancellation. A killed process skips this; the
                // next run's deleteStale / prepareStaging cover that.
                try? FileManager.default.removeItem(at: staging)
            }
            let area = try await attemptBody(staging: staging)
            return .published(area)
        } catch is CancellationError {
            await discardStaging()
            return .cancelled
        } catch let failure as DownloadFailure {
            await discardStaging()
            if Task.isCancelled { return .cancelled }
            return .failed(failure)
        } catch {
            await discardStaging()
            if Task.isCancelled { return .cancelled }
            return .failed(.permanent("unexpected error: \(error)", .unknown))
        }
    }

    private func discardStaging() async {
        // An unreadable journal may own the staging files: the core keeps
        // them then; a failure here never hides the original one.
        let (storage, areaId, id) = (registry.storage, areaId, id)
        _ = try? await blocking { try storage.discardStaging(areaId: areaId, workId: id) }
    }

    /// One attempt: submit (or resume) the SliceOSM job, poll it, download
    /// the PBF, import it into the staging directory, download the basemap
    /// next to it, publish everything in one commit.
    private func attemptBody(staging: URL) async throws -> AreaInfo {
        let (storage, files, areaId, id, request) = (registry.storage, registry.files, areaId, id, request)
        report(.submitting(runId: id))
        try local { try files.prepare(areaId) }
        // A run killed after its commit point recovers the complete area and
        // succeeds without downloading it again.
        if let published = try await local({ try await blocking { try storage.published(areaId: areaId) } }),
           published.metadata?.workId == id {
            return published
        }
        files.deleteStale(areaId, keep: staging)
        _ = try await local { try await blocking { try storage.prepareStaging(areaId: areaId, workId: id) } }
        let (job, slice) = try await sliceJob()

        report(.downloading(runId: id, bytes: 0, totalBytes: slice.sizeBytes))
        let bytes = try await retryingInline {
            try await http.download(job.downloadUrl, to: staging) { [self] bytes, total in
                progress("downloading", .downloading(runId: id, bytes: bytes, totalBytes: total ?? slice.sizeBytes))
            }
        }
        if let size = slice.sizeBytes, bytes != size {
            // A complete body that is not the file the job announced.
            throw DownloadFailure.transient("download size \(bytes) differs from SliceOSM's \(size)", .server)
        }

        report(.importing(runId: id))
        let layout = try local { try storage.layout(areaId: areaId, workId: id) }
        let importReport: ImportReport
        do {
            let (input, output, options) = (staging.path, layout.stagedData!, request.config.importOptions)
            importReport = try await blocking {
                try OsmStore.importArea(input: input, destination: output, options: options)
            }
        } catch {
            // Bad or truncated PBF → INVALID_DATA (permanent); a full disk →
            // STORAGE. The old area is intact.
            throw Failures.failure(error, context: .default, "import failed")
        }
        AreaTestHooks.afterImport?()
        try? FileManager.default.removeItem(at: staging) // free the PBF's space before the basemap
        try Task.checkCancellation() // a cancel during the import ends the run here

        let basemap = try await downloadBasemap(output: URL(fileURLWithPath: layout.stagedBasemap!))
        let metadata = AreaMetadata(
            bbox: request.bbox, name: request.name,
            snapshotTimestamp: AreaMetadata.parseSnapshotTimestamp(slice.timestamp),
            importedAtMillis: Int64(Date().timeIntervalSince1970 * 1000), report: importReport,
            basemap: basemap, workId: id)
        try await local { try await blocking { try storage.writeStagedMetadata(areaId: areaId, workId: id, metadata: metadata) } }
        try await publish(hasBasemap: basemap != nil)
        guard let area = try await local({ try await blocking { try storage.published(areaId: areaId) } }) else {
            throw DownloadFailure(kind: .storage, reason: .storage, message: "published area vanished")
        }
        return area
    }

    /// The commit. The last cancellation check runs under the area lock;
    /// past it the run publishes even if a cancel arrives meanwhile.
    private func publish(hasBasemap: Bool) async throws {
        let (storage, areaId, id, token) = (registry.storage, areaId, id, token)
        try await local {
            try await blocking {
                try storage.commit(areaId: areaId, workId: id, hasBasemap: hasBasemap,
                                   beforeCommit: { try token.enterCommit() },
                                   afterCommitPoint: { AreaTestHooks.afterCommitPoint?() })
            }
        }
    }

    /// Gets a finished SliceOSM job: resumes the checkpointed job of this
    /// run if there is one, otherwise submits a new job. If the server no
    /// longer knows a resumed job, submits once more.
    private func sliceJob() async throws -> (SliceProtocol.Job, SliceProtocol.Progress) {
        let base = request.config.sliceBaseUrl
        let files = registry.files
        var job = files.readCheckpoint(areaId, workId: id, base: base).flatMap { try? SliceProtocol.job(base: base, response: $0) }
        for _ in 0..<2 {
            if job == nil {
                let submit = try protocolCall(transient: false) {
                    try SliceProtocol.jobRequest(base: base, bbox: request.bbox, name: request.name)
                }
                let response = try await retryingInline { try await http.postJSON(submit.url, body: submit.body) }
                // An unparseable answer (an HTML error page) is the server's fault: transient.
                let submitted = try protocolCall(transient: true) { try SliceProtocol.job(base: base, response: response) }
                try local { try files.writeCheckpoint(areaId, workId: id, base: base, jobId: submitted.id) }
                job = submitted
            }
            do {
                return (job!, try await awaitSlice(job!))
            } catch let failure as DownloadFailure where failure.kind == .jobGone {
                job = nil // expired or unknown job: start over
            }
        }
        throw DownloadFailure.transient("SliceOSM lost the job twice", .server)
    }

    private func awaitSlice(_ job: SliceProtocol.Job) async throws -> SliceProtocol.Progress {
        let tuning = DownloadTuning.current
        let started = Date()
        while true {
            let status = try await retryingInline { try await http.getText(job.statusUrl) }
            let progress = try protocolCall(transient: true) { try SliceProtocol.progress(status: status) }
            report(.slicing(runId: id, fraction: progress.fraction))
            if progress.complete { return progress }
            if Date().timeIntervalSince(started) > tuning.maxSliceWait {
                throw DownloadFailure.transient("SliceOSM job \(job.id) still running after \(Int(tuning.maxSliceWait)) s",
                                                .server)
            }
            try await Task.sleep(nanoseconds: UInt64(tuning.pollInterval * 1e9))
        }
    }

    /// Downloads the requested basemap into the staging directory; nil for none.
    private func downloadBasemap(output: URL) async throws -> BasemapMetadata? {
        let id = self.id
        switch request.basemap.kind {
        case .none:
            return nil
        case .url(let url):
            report(.basemap(runId: id, phase: .download, bytes: 0, totalBytes: nil))
            let bytes = try await retryingInline {
                // A 404 here is permanent (wrong URL), not a vanished job.
                try await http.download(url, to: output, goneOn404: false) { [self] bytes, total in
                    progress("basemap-download", .basemap(runId: id, phase: .download, bytes: bytes, totalBytes: total))
                }
            }
            let info: PmtilesInfo
            do {
                info = try await blocking { try PmtilesInfo.read(output.path) }
            } catch {
                // InvalidFile (not PMTiles v3) → INVALID_DATA; Io → STORAGE.
                throw Failures.failure(error, context: .default, "basemap \(url) is not a valid PMTiles v3 archive")
            }
            return BasemapMetadata(kind: .url, sourceUrl: url, fileBytes: info.fileBytes,
                                   addressedTiles: info.addressedTiles, minZoom: info.minZoom, maxZoom: info.maxZoom,
                                   requests: 1, transferredBytes: bytes)
        case .extract(let url, let maxZoom, let overfetch):
            report(.basemap(runId: id, phase: .directories, bytes: 0, totalBytes: nil))
            let extract = BasemapExtract(http: http, workDir: registry.files.directory(areaId))
            let stats = try await extract.run(url: url, bbox: request.bbox, maxZoom: maxZoom, overfetch: overfetch,
                                              output: output) { [self] phase, bytes, total in
                progress("basemap-\(phase.rawValue)", .basemap(runId: id, phase: phase, bytes: bytes, totalBytes: total))
            }
            let info: PmtilesInfo
            do {
                info = try await blocking { try PmtilesInfo.read(output.path) }
            } catch {
                // The engine wrote and validated this file itself: storage
                // trouble or a bug (context "engine": never retried blindly).
                throw Failures.failure(error, context: .engine, "extracted basemap is unreadable")
            }
            return BasemapMetadata(kind: .extract, sourceUrl: url, fileBytes: info.fileBytes,
                                   addressedTiles: stats.addressedTiles, minZoom: info.minZoom, maxZoom: info.maxZoom,
                                   requests: stats.requests, transferredBytes: stats.transferredBytes)
        }
    }

    /// Maps a protocol (core) rejection onto the failure classes: building
    /// the submit request from the app's bbox/name is the request's fault
    /// (permanent), reading a server answer is the server's (transient).
    private func protocolCall<T>(transient: Bool, _ body: () throws -> T) throws -> T {
        do {
            return try body()
        } catch {
            throw Failures.failure(error, context: transient ? .protocolResponse : .protocolRequest, "SliceOSM protocol")
        }
    }

    /// Local storage work (area store, scratch files): an I/O failure is
    /// STORAGE (retried by backoff); other core errors are classified.
    private func local<T>(_ body: () throws -> T) throws -> T {
        do {
            return try body()
        } catch let error as DownloadFailure {
            throw error
        } catch is CancellationError {
            throw CancellationError()
        } catch let error as CantinoError {
            throw Failures.failure(error, context: .default, "storage")
        } catch {
            throw Failures.failure(io: .storage, "storage: \(error.localizedDescription)")
        }
    }

    private func local<T>(_ body: () async throws -> T) async throws -> T {
        do {
            return try await body()
        } catch let error as DownloadFailure {
            throw error
        } catch is CancellationError {
            throw CancellationError()
        } catch let error as CantinoError {
            throw Failures.failure(error, context: .default, "storage")
        } catch {
            throw Failures.failure(io: .storage, "storage: \(error.localizedDescription)")
        }
    }
}
