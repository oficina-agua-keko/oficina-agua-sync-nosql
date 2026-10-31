package gt.edu.miumg.oficinaagua.sync.replicacion;

import java.time.Duration;                                      // CAMBIO 1: para representar la espera de 30 s
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;   // CAMBIO 1: indica qué constructor usa Spring
import org.springframework.beans.factory.annotation.Value;       // CAMBIO 1: lee el retardo desde la configuración
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

import gt.edu.miumg.oficinaagua.sync.Conteo;
import gt.edu.miumg.oficinaagua.sync.Entidad;
import gt.edu.miumg.oficinaagua.sync.ReplicacionException;
import gt.edu.miumg.oficinaagua.sync.Replicador;
import gt.edu.miumg.oficinaagua.sync.ResultadoReplicacion;
import software.amazon.awssdk.core.exception.SdkException;

/**
 * HU-55 y HU-56: la replicación real. Por cada tipo de dato (clientes, consumos y pagos):
 * lee el origen, lee las huellas del destino, calcula la diferencia y escribe solo los registros
 * nuevos y modificados.
 *
 * Es repetible: si se ejecuta dos veces seguidas, la segunda encuentra todo igual y no escribe
 * nada. Si falla a la mitad, lo ya escrito es correcto y la siguiente ejecución continúa.
 *
 * Trabaja en dos fases: primero consulta y calcula qué falta por replicar (y lo deja en el log);
 * después espera un retardo configurable (por defecto 30 segundos, para que se vea que el job
 * sigue EN_PROGRESO mientras la API ya respondió) y recién entonces escribe en DynamoDB.
 */
@Component
public class ReplicadorResumen implements Replicador {

    static final String ERROR_ORIGEN = "No se pudo leer la instancia MySQL de origen.";
    static final String ERROR_LECTURA_DESTINO = "No se pudo consultar el destino en DynamoDB.";
    static final String ERROR_ESCRITURA_DESTINO = "No se pudo escribir en el destino en DynamoDB.";

    private static final Logger log = LoggerFactory.getLogger(ReplicadorResumen.class);

    private final OrigenResumen origen;
    private final DestinoResumen destino;
    // CAMBIO 2: campo nuevo. Cuánto espera el Thread entre consultar lo pendiente y escribirlo.
    private final Duration retardo;

    /**
     * @param retardoSegundos segundos que espera el Thread entre consultar lo pendiente y escribirlo.
     *                        Viene de SYNC_RETARDO_SEGUNDOS (30 por defecto); con 0 no espera.
     */
    // CAMBIO 3: constructor nuevo (el que usa Spring). Recibe los segundos de espera desde
    // sync.retardo-segundos (variable SYNC_RETARDO_SEGUNDOS; 30 por defecto).
    @Autowired
    public ReplicadorResumen(OrigenResumen origen, DestinoResumen destino,
                             @Value("${sync.retardo-segundos:30}") long retardoSegundos) {
        this.origen = origen;
        this.destino = destino;
        this.retardo = Duration.ofSeconds(Math.max(0, retardoSegundos));
    }

    // CAMBIO 4: se conserva el constructor anterior, sin espera, para que las pruebas existentes sigan igual.
    /** Sin retardo: lo que usan las pruebas unitarias. */
    public ReplicadorResumen(OrigenResumen origen, DestinoResumen destino) {
        this(origen, destino, 0);
    }

    @Override
    // CAMBIO 5: replicar() ahora trabaja en dos fases y puede lanzar InterruptedException (por la espera).
    public ResultadoReplicacion replicar() throws ReplicacionException, InterruptedException {
        // CAMBIO 5a - FASE 1: consultar. Por cada tipo de dato se calcula qué falta por replicar.
        // (Antes se leía y se escribía tipo por tipo; ahora primero se consulta todo.)
        Map<Entidad, Diferencia> diferencias = new EnumMap<>(Entidad.class);
        int nuevos = 0;
        int modificados = 0;
        int alDia = 0;
        for (Entidad entidad : Entidad.values()) {
            Diferencia diferencia = Diferencia.calcular(leerOrigen(entidad), leerDestino(entidad));
            diferencias.put(entidad, diferencia);
            nuevos += diferencia.nuevos().size();
            modificados += diferencia.modificados().size();
            alDia += diferencia.iguales();
            log.info("{}: faltan por replicar {} nuevos y {} modificados ({} ya están al día)",
                    entidad, diferencia.nuevos().size(), diferencia.modificados().size(), diferencia.iguales());
        }
        // CAMBIO 6: el log que muestra cuánto falta, ANTES de escribir nada en DynamoDB.
        log.info("Pendiente por replicar: {} registros ({} nuevos y {} modificados); {} ya están al día",
                nuevos + modificados, nuevos, modificados, alDia);

        // CAMBIO 7: pausa del Thread (30 s). El job sigue EN_PROGRESO; la API ya respondió 202.
        esperarRetardo();

        // CAMBIO 5b - FASE 2: replicar. Solo se escriben los nuevos y los modificados.
        Map<Entidad, Conteo> conteos = new EnumMap<>(Entidad.class);
        for (Entidad entidad : Entidad.values()) {
            Diferencia diferencia = diferencias.get(entidad);
            escribir(diferencia.nuevos());
            escribir(diferencia.modificados());
            Conteo conteo = diferencia.conteo();
            conteos.put(entidad, conteo);
            log.info("{}: {} insertados, {} actualizados, {} sin cambios",
                    entidad, conteo.insertados(), conteo.actualizados(), conteo.sinCambios());
        }
        return new ResultadoReplicacion(conteos);
    }

    // CAMBIO 8: método nuevo con la espera. Con retardo 0 no hace nada.
    /**
     * Pausa del Thread de segundo plano. La API ya respondió 202 hace rato: mientras tanto el job
     * sigue EN_PROGRESO y se puede ver con GetJob. Si el servicio se apaga, la interrupción sube a
     * ProcesadorReplicacion, que deja el job FALLIDO.
     */
    private void esperarRetardo() throws InterruptedException {
        if (retardo.isZero()) {
            return;
        }
        log.info("Esperando {} segundos antes de replicar (el job sigue EN_PROGRESO)...", retardo.toSeconds());
        Thread.sleep(retardo.toMillis());
        log.info("Fin de la espera: se empieza a replicar.");
    }

    private List<RegistroResumen> leerOrigen(Entidad entidad) throws ReplicacionException {
        try {
            return origen.leer(entidad);
        } catch (DataAccessException ex) {
            throw new ReplicacionException(ERROR_ORIGEN, ex);
        }
    }

    private Map<String, String> leerDestino(Entidad entidad) throws ReplicacionException {
        try {
            return destino.hashes(entidad);
        } catch (SdkException ex) {
            throw new ReplicacionException(ERROR_LECTURA_DESTINO, ex);
        }
    }

    private void escribir(List<RegistroResumen> registros) throws ReplicacionException {
        try {
            if (!registros.isEmpty()) {
                destino.upsert(registros);
            }
        } catch (SdkException ex) {
            throw new ReplicacionException(ERROR_ESCRITURA_DESTINO, ex);
        }
    }
}
