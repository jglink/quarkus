package io.quarkus.vertx.runtime;

import java.util.Set;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.context.BeforeDestroyed;
import javax.enterprise.context.spi.Context;
import javax.enterprise.event.Observes;
import javax.enterprise.inject.Any;
import javax.enterprise.inject.Produces;
import javax.enterprise.inject.spi.Bean;
import javax.enterprise.inject.spi.BeanManager;
import javax.inject.Inject;
import javax.inject.Singleton;

import org.jboss.logging.Logger;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.Vertx;
import io.vertx.core.eventbus.EventBus;

/**
 * Expose the Vert.x event bus and produces Mutiny, Axle (Deprecated) and Rx Vert.x (Deprecated) instances.
 * <p>
 * The original Vert.x instance is coming from the core artifact.
 *
 * IMPORTANT: The Axle and RxJava 2 API are now deprecated. It is recommended to switch to the Mutiny API.
 * 
 * IMPL NOTE: There is no need to cache the mutiny/axle/rx wrappers locally because the bean instances are stored in the
 * singleton context, i.e. the producer method is only called once.
 */
@ApplicationScoped
public class VertxProducer {

    private static final Logger LOGGER = Logger.getLogger(VertxProducer.class);

    @Inject
    Vertx vertx;

    @Singleton
    @Produces
    public EventBus eventbus() {
        return vertx.eventBus();
    }

    @Singleton
    @Produces
    public io.vertx.mutiny.core.Vertx mutiny() {
        return io.vertx.mutiny.core.Vertx.newInstance(vertx);
    }

    @Singleton
    @Produces
    @Deprecated
    public io.vertx.axle.core.Vertx axle() {
        LOGGER.warn(
                "`io.vertx.axle.core.Vertx` is deprecated and will be removed in a future version - it is "
                        + "recommended to switch to `io.vertx.mutiny.core.Vertx`");
        return io.vertx.axle.core.Vertx.newInstance(vertx);
    }

    @Singleton
    @Produces
    @Deprecated
    public io.vertx.reactivex.core.Vertx rx() {
        LOGGER.warn(
                "`io.vertx.reactivex.core.Vertx` is deprecated  and will be removed in a future version - it is "
                        + "recommended to switch to `io.vertx.mutiny.core.Vertx`");
        return io.vertx.reactivex.core.Vertx.newInstance(vertx);
    }

    @Singleton
    @Produces
    @Deprecated
    public io.vertx.axle.core.eventbus.EventBus axleEventBus(io.vertx.axle.core.Vertx axle) {
        LOGGER.warn(
                "`io.vertx.axle.core.eventbus.EventBus` is deprecated and will be removed in a future version - it is "
                        + "recommended to switch to `io.vertx.mutiny.core.eventbus.EventBus`");
        return axle.eventBus();
    }

    @Singleton
    @Produces
    @Deprecated
    public io.vertx.reactivex.core.eventbus.EventBus rxEventBus(io.vertx.reactivex.core.Vertx rx) {
        LOGGER.warn(
                "`io.vertx.reactivex.core.eventbus.EventBus` is deprecated and will be removed in a future version - it "
                        + "is recommended to switch to `io.vertx.mutiny.core.eventbus.EventBus`");
        return rx.eventBus();
    }

    @Singleton
    @Produces
    public io.vertx.mutiny.core.eventbus.EventBus mutinyEventBus(io.vertx.mutiny.core.Vertx mutiny) {
        return mutiny.eventBus();
    }

    /**
     * Undeploy verticles backed by contextual instances of {@link ApplicationScoped} beans before the application context is
     * destroyed. Otherwise Vertx may attempt to stop the verticles after the CDI container is shut down.
     * 
     * @param event
     * @param beanManager
     */
    void undeployVerticles(@Observes @BeforeDestroyed(ApplicationScoped.class) Object event,
            BeanManager beanManager, io.vertx.mutiny.core.Vertx mutiny) {
        // Only beans with the AbstractVerticle in the set of bean types are considered - we need a deployment id 
        Set<Bean<?>> beans = beanManager.getBeans(AbstractVerticle.class, Any.Literal.INSTANCE);
        Context applicationContext = beanManager.getContext(ApplicationScoped.class);
        for (Bean<?> bean : beans) {
            if (ApplicationScoped.class.equals(bean.getScope())) {
                // Only beans with @ApplicationScoped are considered
                Object instance = applicationContext.get(bean);
                if (instance != null) {
                    // Only existing instances are considered
                    try {
                        AbstractVerticle verticle = (AbstractVerticle) instance;
                        mutiny.undeploy(verticle.deploymentID()).await().indefinitely();
                        LOGGER.debugf("Undeployed verticle: %s", instance.getClass());
                    } catch (Exception e) {
                        // In theory, a user can undeploy the verticle manually
                        LOGGER.debugf("Unable to undeploy verticle %s: %s", instance.getClass(), e.toString());
                    }
                }
            }
        }
    }

}
