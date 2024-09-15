# Temporal Component

Adds a Temporal codec server, middleware, and convenience methods for
calling the Temporal SDK.

See example configuration in the `resources/config.example.edn` file.

## use-temporal component

A Biff component which will add a Temporal client and worker clients
to application context.

## Codec Server

A Biff module with a `/codec/decode` route and middleware to add CORS headers.

http://localhost:8080/codec

Make sure the CORS  middleware is before `muuntaja/wrap-response` in your `default-api-middleware`.
I haven't included the middleware in the module until I can make this requirement go away.
