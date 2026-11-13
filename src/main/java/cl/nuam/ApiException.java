package cl.nuam;

import java.util.LinkedHashMap;
import java.util.Map;

/** Error de negocio/HTTP con código de estado y mensaje legible para el usuario. */
public class ApiException extends RuntimeException {
    public final int status;
    public final Map<String, Object> extra = new LinkedHashMap<>();
    public ApiException(int status, String message) { super(message); this.status = status; }
    public ApiException with(String k, Object v) { extra.put(k, v); return this; }
    public static ApiException bad(String m) { return new ApiException(400, m); }
    public static ApiException invalid(String m) { return new ApiException(422, m); }
    public static ApiException forbidden(String m) { return new ApiException(403, m); }
    public static ApiException notFound(String m) { return new ApiException(404, m); }
}
