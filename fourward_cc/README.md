# C++ embedding API

Treat 4ward like a native C++ library. `FourwardServer` is an RAII handle
to a 4ward gRPC server running as a child process — your project sees a
C++ API and a Bazel target; the server's implementation language never
enters the picture.

`DataplaneClient` wraps the Dataplane gRPC service with an ergonomic C++
interface for packet injection and result observation.

See the [embedding guide](../userdocs/reference/embedding-cc.md).
