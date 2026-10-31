package kz.kbtu.blog.api.rest;

/** The code uses the gRPC status names, so both protocols report failures the same way. */
public record ErrorResponse(String code, String message) {
}
