package parcial3.controladores;

import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.UnauthorizedResponse;
import parcial3.entidades.Acceso;
import parcial3.entidades.Url;
import parcial3.entidades.Usuario;
import parcial3.servicios.JWTService;
import parcial3.servicios.MongoGestionDb;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Controlador REST para autenticación (JWT).
 */
public class ApiAuthController {

    private final MongoGestionDb<Usuario> usuarioDb;
    private final MongoGestionDb<Url> urlDb;
    private final MongoGestionDb<Acceso> accesoDb;
    private final Random random = new Random();
    private static final String CARACTERES = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefhijklmnopqrstguvwxyz0123456789";
    private static final int LONGITUD_CODIGO = 7;

    public ApiAuthController() {
        this.usuarioDb = new MongoGestionDb<>(Usuario.class, "usuarios");
        this.urlDb = new MongoGestionDb<>(Url.class, "urls"); // Inicializar
        this.accesoDb = new MongoGestionDb<>(Acceso.class, "accesos"); // Inicializar
    }

    public void init(Javalin app) {

        // Login via JSON: { "username": "...", "password": "..." }
        app.post("/api/v1/login", ctx -> {
            Map<String, String> json = ctx.bodyAsClass(Map.class);
            String username = json.get("username");
            String password = json.get("password");

            Usuario usuario = usuarioDb.findAll().stream()
                    .filter(u -> u.getUsername().equals(username) && u.getPassword().equals(password))
                    .findFirst()
                    .orElse(null);

            if (usuario == null) {
                ctx.status(401).json(Map.of("error", "Credenciales inválidas."));
                return;
            }

            // Generar un JWT
            String token = JWTService.generateToken(usuario.getUsername());

            // Retornar el token en JSON
            ctx.json(Map.of("token", token));
        });

        // (Opcional) Crear usuario via REST:
        app.post("/api/v1/register", ctx -> {
            Map<String, String> json = ctx.bodyAsClass(Map.class);
            String nombre = json.get("nombre");
            String username = json.get("username");
            String password = json.get("password");

            // Verificar si existe
            boolean existe = usuarioDb.findAll().stream()
                    .anyMatch(u -> u.getUsername().equals(username));
            if (existe) {
                ctx.status(409).json(Map.of("error", "Usuario ya existe"));
                return;
            }

            Usuario nuevo = new Usuario();
            nuevo.setNombre(nombre);
            nuevo.setUsername(username);
            nuevo.setPassword(password);
            nuevo.setAdmin(false); // o lo que desees

            usuarioDb.crear(nuevo);
            ctx.json(Map.of("status", "ok", "message", "Usuario creado"));
        });

        app.get("/api/v1/rest/urls", ctx -> {
            String token = ctx.header("Authorization").replace("Bearer ", "");
            String username = JWTService.validateToken(token);

            Usuario usuario = usuarioDb.findAll().stream()
                    .filter(u -> u.getUsername().equals(username))
                    .findFirst()
                    .orElseThrow(() -> new UnauthorizedResponse("Usuario no válido"));

            List<Url> urls = urlDb.findAll().stream()
                    .filter(url -> url.getUsuario() != null && url.getUsuario().getId().equals(usuario.getId()))
                    .toList();

            String host = obtenerHost(ctx);

            List<Map<String, Object>> respuesta = urls.stream().map(url -> {
                Map<String, Object> stats = new HashMap<>();
                List<Acceso> accesos = accesoDb.findAll().stream()
                        .filter(a -> a.getUrl().getId().equals(url.getId()))
                        .toList();

                stats.put("totalAccesos", accesos.size());
                stats.put("navegadores", accesos.stream()
                        .collect(Collectors.groupingBy(Acceso::getNavegador, Collectors.counting())));
                stats.put("sistemasOperativos", accesos.stream()
                        .collect(Collectors.groupingBy(Acceso::getSistemaOperativo, Collectors.counting())));

                return Map.of(
                        "urlCompleta", "https://" + host + "/" + url.getShortUrl(),
                        "urlAcortada", url.getShortUrl(),
                        "fechaCreacion", url.getFechaCreacion(),
                        "estadisticas", stats
                );
            }).toList();

            ctx.json(respuesta);
        });

        app.post("/api/v1/rest/urls", ctx -> {
            String token = ctx.header("Authorization").replace("Bearer ", "");
            String username = JWTService.validateToken(token);

            Usuario usuario = usuarioDb.findAll().stream()
                    .filter(u -> u.getUsername().equals(username))
                    .findFirst()
                    .orElseThrow(() -> new UnauthorizedResponse("Usuario no válido"));

            Map<String, String> body = ctx.bodyAsClass(Map.class);
            String urlOriginal = body.get("urlOriginal");

            Url nuevaUrl = new Url();
            nuevaUrl.setUrl(urlOriginal);
            nuevaUrl.setShortUrl(generarCodigoUnico());
            nuevaUrl.setUsuario(usuario);
            nuevaUrl.setFechaCreacion(LocalDate.now());

            urlDb.crear(nuevaUrl);

            String host = obtenerHost(ctx);

            Map<String, Object> respuesta = Map.of(
                    "urlCompleta", "https://" + host + "/" + nuevaUrl.getShortUrl(),
                    "urlAcortada", nuevaUrl.getShortUrl(),
                    "fechaCreacion", nuevaUrl.getFechaCreacion(),
                    "estadisticas", Map.of(
                            "totalAccesos", 0,
                            "navegadores", Collections.emptyMap(),
                            "sistemasOperativos", Collections.emptyMap()
                    )
            );

            ctx.json(respuesta);
        });
    }

    private String generarCodigoUnico() {
        String codigo;
        do {
            codigo = generarCodigoAleatorio();
        } while (urlExiste(codigo));
        return codigo;
    }

    private boolean urlExiste(String codigo) {
        return urlDb.findAll().stream()
                .anyMatch(url -> url.getShortUrl().equals(codigo));
    }

    private String generarCodigoAleatorio() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < LONGITUD_CODIGO; i++) {
            sb.append(CARACTERES.charAt(random.nextInt(CARACTERES.length())));
        }
        return sb.toString();
    }

    private String obtenerHost(Context ctx) {
        return ctx.header("X-Forwarded-Host") != null ?
                ctx.header("X-Forwarded-Host") :
                (ctx.header("Host") != null ? ctx.header("Host") : "localhost");
    }
}
