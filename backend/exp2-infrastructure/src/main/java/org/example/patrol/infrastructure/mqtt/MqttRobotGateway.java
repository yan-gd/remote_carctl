package org.example.patrol.infrastructure.mqtt;

import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.example.patrol.domain.*;
import org.example.patrol.domain.gateway.*;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Paho based MQTT link to the robot bridge.
 *
 * <ul>
 *   <li>Control messages use QoS 1 and are never retained, so a reconnect can never replay an old
 *       FORWARD. Each carries this process's session id and an increasing seq, assigned under the
 *       same lock as the publish so that wire order equals seq order; the bridge drops stale seqs.
 *   <li>Clean session and in-memory persistence: nothing is queued while disconnected, so
 *       publishing fails fast and the API answers 503 instead of sending late commands.
 *   <li>Connection attempts (first one and after a loss) repeat at a fixed, configurable interval
 *       on a daemon thread; startup never waits for the broker.
 *   <li>Inbound messages are handled on one separate thread, keeping Paho's callback thread free
 *       while the listener stores alarms.
 * </ul>
 */
public class MqttRobotGateway implements RobotGateway, AutoCloseable {
    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(MqttRobotGateway.class);
    private static final int QOS = 1;
    private static final RobotListener NOBODY =
            new RobotListener() {
                public void onLink(boolean connected) {}

                public void onHeartbeat(RobotHeartbeat heartbeat) {}

                public void onStatus(RobotTelemetry telemetry) {}

                public void onAck(RobotAck ack) {}
            };

    private final MqttSettings settings;
    private final RobotMessages messages;
    private final String session = UUID.randomUUID().toString();
    private final AtomicLong sequence = new AtomicLong();
    private final AtomicLong rejected = new AtomicLong();
    private final Object publishLock = new Object();
    private final String controlTopic;
    private final String statusTopic;
    private final String heartbeatTopic;
    private final String ackTopic;
    private final MqttAsyncClient client;
    private final ScheduledExecutorService retries =
            Executors.newSingleThreadScheduledExecutor(daemon("mqtt-connect"));
    private final ExecutorService inbound = Executors.newSingleThreadExecutor(daemon("mqtt-inbound"));
    private volatile RobotListener listener = NOBODY;
    private volatile boolean closed;
    private boolean started; // guarded by this

    public MqttRobotGateway(MqttSettings settings) {
        this.settings = settings;
        this.messages = new RobotMessages(settings.robotId());
        this.controlTopic = settings.topic("control");
        this.statusTopic = settings.topic("status");
        this.heartbeatTopic = settings.topic("heartbeat");
        this.ackTopic = settings.topic("control/ack");
        try {
            this.client =
                    new MqttAsyncClient(
                            settings.brokerUrl(), settings.clientId(), new MemoryPersistence());
        } catch (MqttException e) {
            throw new IllegalArgumentException("无法创建MQTT客户端：" + settings.brokerUrl(), e);
        }
        client.setCallback(new Callback());
    }

    /** Session id written into every control message of this process. */
    public String session() {
        return session;
    }

    /** Start connecting in the background. */
    public synchronized void start() {
        if (started || closed) return;
        started = true;
        log.info("MQTT robot link: {}", settings);
        connect();
    }

    @Override
    public boolean connected() {
        return !closed && client.isConnected();
    }

    @Override
    public long publish(RobotCommand command, Instant issuedAt) {
        if (!connected()) throw unavailable("MQTT服务不可用，命令未下发");
        IMqttDeliveryToken token;
        long seq;
        synchronized (publishLock) {
            seq = sequence.incrementAndGet();
            try {
                token =
                        client.publish(
                                controlTopic,
                                messages.control(command, issuedAt, seq, session),
                                QOS,
                                false);
            } catch (MqttException e) {
                log.warn("MQTT publish of {} failed ({})", command, describe(e));
                throw unavailable("MQTT服务不可用，命令未下发");
            }
        }
        try {
            token.waitForCompletion(settings.publishTimeoutMs());
        } catch (MqttException e) {
            log.warn("MQTT broker did not confirm {} seq={} ({})", command, seq, describe(e));
            throw unavailable("MQTT Broker未在" + settings.publishTimeoutMs() + "毫秒内确认命令");
        }
        return seq;
    }

    @Override
    public void listen(RobotListener listener) {
        this.listener = listener == null ? NOBODY : listener;
    }

    @Override
    public void close() {
        synchronized (this) {
            if (closed) return;
            closed = true;
        }
        retries.shutdownNow();
        try {
            if (client.isConnected()) client.disconnect(0).waitForCompletion(1000);
        } catch (MqttException e) {
            log.debug("MQTT disconnect: {}", describe(e));
        }
        try {
            client.close(true);
        } catch (MqttException e) {
            log.debug("MQTT close: {}", describe(e));
        }
        inbound.shutdown();
        try {
            inbound.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void connect() {
        if (closed) return;
        try {
            client.connect(
                    options(),
                    null,
                    new IMqttActionListener() {
                        public void onSuccess(IMqttToken token) {
                            // connectComplete() subscribes and reports the link.
                        }

                        public void onFailure(IMqttToken token, Throwable e) {
                            retryLater("connect to " + settings.brokerUrl() + " failed", e);
                        }
                    });
        } catch (MqttException e) {
            retryLater("connect to " + settings.brokerUrl() + " failed", e);
        }
    }

    private MqttConnectOptions options() {
        var options = new MqttConnectOptions();
        options.setCleanSession(true);
        options.setAutomaticReconnect(false); // fixed-interval retries are done in retryLater()
        options.setKeepAliveInterval(settings.keepAliveSeconds());
        options.setConnectionTimeout(settings.connectTimeoutSeconds());
        options.setMaxInflight(32);
        if (settings.username() != null) options.setUserName(settings.username());
        if (settings.password() != null) options.setPassword(settings.password().toCharArray());
        return options;
    }

    private void retryLater(String event, Throwable cause) {
        if (closed) return;
        log.warn(
                "MQTT {} ({}); reconnecting in {} ms",
                event,
                describe(cause),
                settings.reconnectIntervalMs());
        dispatch(l -> l.onLink(false));
        schedule(this::connect);
    }

    private void subscribe() {
        try {
            client.subscribe(
                    new String[] {heartbeatTopic, statusTopic, ackTopic},
                    new int[] {QOS, QOS, QOS},
                    null,
                    new IMqttActionListener() {
                        public void onSuccess(IMqttToken token) {
                            for (int granted : token.getGrantedQos())
                                if (granted == 0x80) {
                                    log.error("MQTT broker refused a robot subscription (ACL?)");
                                    resubscribeLater();
                                    return;
                                }
                            log.info("MQTT subscribed to robot/{}/* topics", settings.robotId());
                        }

                        public void onFailure(IMqttToken token, Throwable e) {
                            log.error("MQTT subscribe failed ({})", describe(e));
                            resubscribeLater();
                        }
                    });
        } catch (MqttException e) {
            log.error("MQTT subscribe failed ({})", describe(e));
            resubscribeLater();
        }
    }

    private void resubscribeLater() {
        schedule(
                () -> {
                    if (connected()) subscribe();
                });
    }

    private void schedule(Runnable task) {
        if (closed) return;
        try {
            retries.schedule(task, settings.reconnectIntervalMs(), TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException ignored) {
            // closed concurrently
        }
    }

    private void dispatch(Consumer<RobotListener> event) {
        if (closed) return;
        try {
            inbound.execute(
                    () -> {
                        try {
                            event.accept(listener);
                        } catch (RuntimeException e) {
                            log.error(
                                    "Robot message handling failed ({})",
                                    e.getClass().getSimpleName());
                        }
                    });
        } catch (RejectedExecutionException ignored) {
            // closed concurrently
        }
    }

    private void deliver(RobotListener l, String topic, byte[] payload) {
        boolean accepted = false;
        if (heartbeatTopic.equals(topic)) {
            var heartbeat = messages.heartbeat(payload);
            accepted = heartbeat != null;
            if (accepted) l.onHeartbeat(heartbeat);
        } else if (statusTopic.equals(topic)) {
            var status = messages.status(payload);
            accepted = status != null;
            if (accepted) l.onStatus(status);
        } else if (ackTopic.equals(topic)) {
            var ack = messages.ack(payload, session);
            accepted = ack != null;
            if (accepted) l.onAck(ack);
        }
        // Throttled: a misconfigured bridge publishing garbage at 2 Hz must not flood the log.
        if (!accepted && rejected.incrementAndGet() % 100 == 1)
            log.warn("Ignored malformed, foreign or stale message on {}", topic);
    }

    private final class Callback implements MqttCallbackExtended {
        @Override
        public void connectComplete(boolean reconnect, String serverUri) {
            if (closed) {
                try {
                    client.disconnect(0);
                } catch (MqttException ignored) {
                    // shutting down anyway
                }
                return;
            }
            log.info("MQTT connected to {}", serverUri);
            subscribe();
            dispatch(l -> l.onLink(true));
        }

        @Override
        public void connectionLost(Throwable cause) {
            retryLater("connection lost", cause);
        }

        @Override
        public void messageArrived(String topic, MqttMessage message) {
            if (message.isRetained()) return; // only live robot messages count
            byte[] payload = message.getPayload();
            dispatch(l -> deliver(l, topic, payload));
        }

        @Override
        public void deliveryComplete(IMqttDeliveryToken token) {
            // publish() waits on its own token.
        }
    }

    private static BusinessException unavailable(String message) {
        return BusinessException.unavailable("MQTT_UNAVAILABLE", message);
    }

    private static String describe(Throwable e) {
        if (e instanceof MqttException m) return "reason " + m.getReasonCode() + ": " + m.getMessage();
        return e == null ? "unknown" : e.getClass().getSimpleName();
    }

    private static ThreadFactory daemon(String name) {
        return task -> {
            Thread thread = new Thread(task, name);
            thread.setDaemon(true);
            return thread;
        };
    }
}
