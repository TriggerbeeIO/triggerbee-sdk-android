package com.triggerbee.sdk

/**
 * Errors surfaced by the Triggerbee SDK. Sealed so consumers can exhaustively `when` over
 * them; all network-related calls translate underlying [java.io.IOException]s,
 * [retrofit2.HttpException]s, and serialization errors into one of these subclasses.
 */
public sealed class TriggerbeeException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause) {

    /**
     * A method was called before [Triggerbee.init]. Configure the SDK first
     * (typically in `Application.onCreate`).
     */
    public class NotInitialized internal constructor() :
        TriggerbeeException("Triggerbee.init(...) must be called before any other SDK method")

    /**
     * The backend returned a non-2xx status code. [body] is the response body for
     * diagnostics — may be empty.
     */
    public class HttpError internal constructor(
        public val code: Int,
        public val body: String,
    ) : TriggerbeeException("HTTP $code — $body")

    /**
     * Connection failed, request timed out, or the device is offline.
     */
    public class NetworkError internal constructor(cause: Throwable) :
        TriggerbeeException("Network error: ${cause.message ?: cause::class.simpleName ?: "unknown"}", cause)

    /**
     * The response was 2xx but the body couldn't be parsed into the expected shape — usually
     * a wire-protocol bug between SDK and backend.
     */
    public class SerializationError internal constructor(cause: Throwable) :
        TriggerbeeException("Failed to parse response: ${cause.message}", cause)
}
