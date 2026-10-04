import Testing
@testable import Cantino

// The classification table is the core's (src/failure.rs; the parity corpus
// pins every row). These check the typed wrapper: that each helper builds
// the input it claims to and reads the answer back.

@Suite struct FailuresTests {
    @Test func httpStatuses() throws {
        #expect(try Failures.http(200, context: .job) == nil)
        #expect(try Failures.http(206, context: .range) == nil)
        let gone = try #require(try Failures.http(404, context: .job))
        #expect(gone.failureClass == .jobGone)
        #expect(gone.reason == .server)
        #expect(gone.schedulerRetry && !gone.inlineRetry)
        let busy = try #require(try Failures.http(503, context: .request))
        #expect(busy.failureClass == .transient)
        #expect(busy.inlineRetry && busy.schedulerRetry)
        let bad = try #require(try Failures.http(400, context: .request))
        #expect(bad.failureClass == .permanent)
        #expect(!bad.schedulerRetry)
    }

    @Test func ioErrors() throws {
        let network = try Failures.io(.network)
        #expect(network.failureClass == .transient)
        #expect(network.reason == .network)
        let storage = try Failures.io(.storage)
        #expect(storage.failureClass == .storage)
        #expect(storage.reason == .storage)
        #expect(!storage.inlineRetry && storage.schedulerRetry)
    }

    @Test func nativeErrors() throws {
        // An I/O error in an import or an extract is the device's storage ...
        for context in [Failures.NativeContext.default, .engine] {
            #expect(try Failures.native(CantinoError.io("disk full"), context: context).failureClass == .storage)
            #expect(try Failures.native(code: 3, context: context).failureClass == .storage)
        }
        // ... but a protocol (SliceOSM) call failing that way is not.
        #expect(try Failures.native(CantinoError.io("x"), context: .protocolRequest).failureClass == .permanent)
        // A bad file repeats on retry.
        #expect(try Failures.native(CantinoError.invalidFile("x"), context: .default).failureClass == .permanent)
    }

    @Test func aNativeCodeOutsideTheTableIsInvalid() {
        #expect(category(caught { try Failures.native(code: 0, context: .default) }) == "INVALID_ARGUMENT")
        #expect(category(caught { try Failures.native(code: 6, context: .default) }) == "INVALID_ARGUMENT")
    }
}
