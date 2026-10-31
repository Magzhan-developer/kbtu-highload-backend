package kz.kbtu.blog.client;

/**
 * Outcome of one logical call.
 *
 * @param id the resource id, or the first item's id for a list (0 if the list is empty)
 * @param requestBytes serialized body size: JSON or Protobuf, without HTTP or gRPC framing
 * @param responseBytes same as {@code requestBytes}, for the response
 */
public record CallResult(long id, int requestBytes, int responseBytes) {
}
