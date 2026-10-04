import Foundation

/// One dedicated OS thread that runs submitted blocks one at a time, in
/// submission order: the Swift counterpart of the Kotlin wrapper's
/// `Executors.newSingleThreadExecutor()`.
///
/// Why a real thread and not an actor's default executor: an actor
/// guarantees mutual exclusion, not a fixed thread, and the core confines a
/// store handle to one OS thread. (A custom actor executor pinned to a
/// thread would also work, but `SerialExecutor`/`ExecutorJob` need
/// iOS 17 / macOS 14; this works on every platform the package targets.)
///
/// Thread safety: `queue` and `stopping` are only touched with `condition`
/// locked; the blocks themselves run on the owner thread without the lock.
final class OwnerThread: @unchecked Sendable {
    private let condition = NSCondition()
    private var queue: [() -> Void] = []
    private var stopping = false

    init(name: String) {
        let thread = Thread { [self] in loop() }
        thread.name = name
        thread.start()
    }

    /// Owner-thread body: take the next block, run it, until shut down with
    /// an empty queue (work queued before `shutdown()` still runs).
    private func loop() {
        while true {
            condition.lock()
            while queue.isEmpty && !stopping { condition.wait() }
            if queue.isEmpty { // and stopping
                condition.unlock()
                return
            }
            let job = queue.removeFirst()
            condition.unlock()
            job()
        }
    }

    /// Runs `body` on the owner thread and returns its result (or rethrows
    /// its error) to the awaiting task. Not cancellable once submitted, like
    /// the Kotlin wrapper's calls: a native call cannot be interrupted, and
    /// abandoning its result could leak a handle.
    ///
    /// After `shutdown()` nothing runs any more and this throws
    /// `CantinoStateError.closed` (a caller can pass its own closed check
    /// and then lose a race with a concurrent close; it must not hang).
    func run<T: Sendable>(_ body: @escaping @Sendable () throws -> T) async throws -> T {
        try await withCheckedThrowingContinuation { continuation in
            let accepted = submit { continuation.resume(with: Result { try body() }) }
            if !accepted { continuation.resume(throwing: CantinoStateError.closed("AsyncOsmStore is closed")) }
        }
    }

    /// Queues `job`; false (and `job` dropped) once shut down.
    @discardableResult
    func submit(_ job: @escaping () -> Void) -> Bool {
        condition.lock()
        defer { condition.unlock() }
        if stopping { return false }
        queue.append(job)
        condition.signal()
        return true
    }

    /// Lets the thread finish the queued work and exit. Idempotent.
    func shutdown() {
        condition.lock()
        stopping = true
        condition.signal()
        condition.unlock()
    }
}
