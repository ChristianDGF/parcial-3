package parcial3.servicios;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import org.bson.types.ObjectId;
import parcial3.entidades.Acceso;
import parcial3.entidades.Url;
import parcial3.entidades.Usuario;
import parcial3.grpc.CreateUrlRequest;
import parcial3.grpc.CreateUrlResponse;
import parcial3.grpc.ListUrlsRequest;
import parcial3.grpc.ListUrlsResponse;
import parcial3.grpc.UrlData;
import parcial3.grpc.UrlServiceGrpc;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.Collectors;

/**
 * Implementación del servicio gRPC definido en url.proto
 */
public class UrlGrpcService extends UrlServiceGrpc.UrlServiceImplBase {

    private final MongoGestionDb<Url> urlDb;
    private final MongoGestionDb<Usuario> usuarioDb;
    private final MongoGestionDb<Acceso> accesoDb;
    private final Random random = new Random();

    public UrlGrpcService() {
        this.urlDb = new MongoGestionDb<>(Url.class, "urls");
        this.usuarioDb = new MongoGestionDb<>(Usuario.class, "usuarios");
        this.accesoDb = new MongoGestionDb<>(Acceso.class, "accesos");
    }

    @Override
    public void listUrls(ListUrlsRequest request, StreamObserver<ListUrlsResponse> responseObserver) {
        try {
            String userId = request.getUserId();
            Usuario usuario = usuarioDb.find(new ObjectId(userId));

            if (usuario == null) {
                responseObserver.onError(Status.NOT_FOUND
                        .withDescription("Usuario no encontrado")
                        .asRuntimeException());
                return;
            }

            List<Url> urls = urlDb.findAll().stream()
                    .filter(u -> u.getUsuario().getId().equals(usuario.getId()))
                    .toList();

            List<UrlData> urlDataList = urls.stream().map(url -> {
                List<Acceso> accesos = accesoDb.findAll().stream()
                        .filter(a -> a.getUrl().getId().equals(url.getId()))
                        .toList();

                // Calcular estadísticas
                Map<String, Long> navegadores = accesos.stream()
                        .collect(Collectors.groupingBy(Acceso::getNavegador, Collectors.counting()));
                Map<String, Long> sistemasOp = accesos.stream()
                        .collect(Collectors.groupingBy(Acceso::getSistemaOperativo, Collectors.counting()));

                return UrlData.newBuilder()
                        .setId(url.getId().toHexString())
                        .setUrl(url.getUrl())
                        .setShortUrl(url.getShortUrl())
                        .setUserId(userId)
                        .setFechaCreacion(url.getFechaCreacion().format(DateTimeFormatter.ISO_DATE))
                        .putAllNavegadores(navegadores)
                        .putAllSistemasOperativos(sistemasOp)
                        .setTotalAccesos(accesos.size())
                        .build();
            }).collect(Collectors.toList());

            ListUrlsResponse response = ListUrlsResponse.newBuilder()
                    .addAllUrls(urlDataList)
                    .build();

            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } catch (Exception e) {
            responseObserver.onError(Status.INTERNAL.withDescription("Error al listar URLs").asRuntimeException());
        }
    }

    @Override
    public void createUrl(CreateUrlRequest request, StreamObserver<CreateUrlResponse> responseObserver) {
        try {
            Usuario usuario = usuarioDb.find(new ObjectId(request.getUserId()));
            if (usuario == null) {
                responseObserver.onError(Status.NOT_FOUND
                        .withDescription("Usuario no encontrado")
                        .asRuntimeException());
                return;
            }

            Url nueva = new Url();
            nueva.setUrl(request.getUrlOriginal());
            nueva.setShortUrl(generarCodigoUnico()); // Usar el método aleatorio
            nueva.setUsuario(usuario);
            nueva.setFechaCreacion(LocalDate.now());
            urlDb.crear(nueva);

            // Respuesta con estadísticas vacías
            UrlData data = UrlData.newBuilder()
                    .setId(nueva.getId().toHexString())
                    .setUrl(nueva.getUrl())
                    .setShortUrl(nueva.getShortUrl())
                    .setUserId(request.getUserId())
                    .setFechaCreacion(nueva.getFechaCreacion().format(DateTimeFormatter.ISO_DATE))
                    .putAllNavegadores(Collections.emptyMap())
                    .putAllSistemasOperativos(Collections.emptyMap())
                    .setTotalAccesos(0)
                    .build();

            CreateUrlResponse response = CreateUrlResponse.newBuilder()
                    .setUrl(data)
                    .build();

            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } catch (Exception e) {
            responseObserver.onError(Status.INTERNAL.withDescription("Error al crear URL").asRuntimeException());
        }
    }

    private UrlData toUrlData(Url url) {
        return UrlData.newBuilder()
                .setId(url.getId().toHexString())
                .setUrl(url.getUrl() != null ? url.getUrl() : "")
                .setShortUrl(url.getShortUrl() != null ? url.getShortUrl() : "")
                .setUserId(url.getUsuario().getId().toHexString())
                .setFechaCreacion(
                        url.getFechaCreacion() != null
                                ? url.getFechaCreacion().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
                                : ""
                )
                .build();
    }

    private String generarCodigoUnico() {
        String codigo;
        int intentos = 0;
        do {
            if (intentos++ > 10) { // Límite de intentos
                throw new RuntimeException("No se pudo generar código único");
            }
            codigo = generarCodigoAleatorio();
        } while (urlExiste(codigo));
        return codigo;
    }

    private String generarCodigoAleatorio() {
        final String CARACTERES = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 7; i++) {
            sb.append(CARACTERES.charAt(random.nextInt(CARACTERES.length())));
        }
        return sb.toString();
    }

    private boolean urlExiste(String codigo) {
        return urlDb.findAll().stream().anyMatch(u -> u.getShortUrl().equals(codigo));
    }
}
