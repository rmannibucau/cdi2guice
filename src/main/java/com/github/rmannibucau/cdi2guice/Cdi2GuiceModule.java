package com.github.rmannibucau.cdi2guice;

import static java.util.Collections.emptyList;
import static java.util.Collections.list;
import static java.util.Collections.singletonList;
import static java.util.Comparator.comparing;
import static java.util.Optional.ofNullable;
import static java.util.function.Function.identity;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;
import static java.util.stream.Collectors.toSet;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import java.lang.annotation.Annotation;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.security.Principal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Objects;
import java.util.Properties;
import java.util.function.Function;
import java.util.stream.Stream;

import javax.enterprise.context.Conversation;
import javax.enterprise.context.Dependent;
import javax.enterprise.context.spi.Contextual;
import javax.enterprise.context.spi.CreationalContext;
import javax.enterprise.event.Event;
import javax.enterprise.event.Observes;
import javax.enterprise.inject.Any;
import javax.enterprise.inject.Default;
import javax.enterprise.inject.Instance;
import javax.enterprise.inject.Vetoed;
import javax.enterprise.inject.se.SeContainer;
import javax.enterprise.inject.se.SeContainerInitializer;
import javax.enterprise.inject.spi.AfterBeanDiscovery;
import javax.enterprise.inject.spi.AnnotatedType;
import javax.enterprise.inject.spi.Bean;
import javax.enterprise.inject.spi.BeanManager;
import javax.enterprise.inject.spi.EventMetadata;
import javax.enterprise.inject.spi.Extension;
import javax.enterprise.inject.spi.InjectionPoint;
import javax.enterprise.inject.spi.InjectionTarget;

import com.google.inject.AbstractModule;
import com.google.inject.Inject;
import com.google.inject.Injector;
import com.google.inject.Provider;
import com.google.inject.TypeLiteral;

import lombok.Getter;

public class Cdi2GuiceModule extends AbstractModule implements AutoCloseable {
    private SeContainer container;
    private final Collection<Runnable> onClose = new ArrayList<>();
    private final GuiceContextAccessor accessor = new GuiceContextAccessor();

    @Override
    protected void configure() {
        bind(GuiceContextAccessor.class).toInstance(accessor);
        container = configuredContainer();
        onClose.add(container::close);
    }

    @Getter
    @Vetoed
    public static class GuiceContextAccessor {
        @Inject
        private Provider<Injector> injector;
    }

    /**
     * Injects CDI instances in this instance (@Inject on fields).
     * Note it also manages postconstruct and predestroy properly.
     *
     * @param instance the instance to inject.
     * @param <T> the type of the instance.
     * @return the instance itself.
     */
    public <T> T inject(final T instance) {
        final Class<T> type = Class.class.cast(instance.getClass());
        final BeanManager beanManager = container.getBeanManager();
        final AnnotatedType<T> annotatedType = beanManager.createAnnotatedType(type);
        final InjectionTarget<T> injectionTarget = beanManager.createInjectionTarget(annotatedType);
        final CreationalContext<T> creationalContext = beanManager.createCreationalContext(null);
        injectionTarget.inject(instance, creationalContext);
        injectionTarget.postConstruct(instance);
        onClose.add(() -> {
            injectionTarget.preDestroy(instance);
            injectionTarget.dispose(instance);
            creationalContext.release();
        });
        return instance;
    }

    @Override
    public void close() {
        onClose.forEach(this::doClose);
    }

    protected SeContainer configuredContainer() {
        final SeContainerInitializer initializer = SeContainerInitializer.newInstance();
        final ClassLoader loader = Thread.currentThread().getContextClassLoader();
        initializer.setClassLoader(loader);
        initializer.addExtensions(new Extension() {
            void addGuiceBeans(@Observes final AfterBeanDiscovery afterBeanDiscovery) {
                afterBeanDiscovery.addBean()
                  .id(Cdi2GuiceModule.this.getClass().getName() + "#Injector")
                  .scope(Dependent.class) // don't proxy
                  .types(Injector.class, Object.class)
                  .qualifiers(Default.Literal.INSTANCE, Any.Literal.INSTANCE)
                  .createWith(c -> accessor.getInjector().get());
            }
        });
        final Registrations registrations = Stream.of("", "/")
                .map(prefix -> prefix + "META-INF/cdi2guice/container.properties")
                .map(resource -> {
                    try {
                        return loader.getResources(resource);
                    } catch (final IOException e) {
                        throw new IllegalStateException(e);
                    }
                })
                .flatMap(urls -> list(urls).stream())
                .distinct()
                .map(url -> {
                    final Properties properties = new Properties();
                    try (final InputStream stream = url.openStream()) {
                        properties.load(stream);
                    } catch (final IOException e) {
                        throw new IllegalStateException(e);
                    }
                    return properties;
                })
                .sorted(comparing(props -> Integer.parseInt(props.getProperty("configuration.order", "0"))))
                .map(config -> {
                    final Class<?>[] classes = loadClasses(loader, config.getProperty("beanClasses", ""));
                    if (classes.length > 0) {
                        initializer.addBeanClasses(classes);
                    }
                    final Class<?>[] classPackages = loadClasses(loader, config.getProperty("classPackages", ""));
                    if (classPackages.length > 0) {
                        initializer.addPackages(classPackages);
                    }
                    final Class<?>[] recursiveClassPackages = loadClasses(loader, config.getProperty("recursiveClassPackages", ""));
                    if (recursiveClassPackages.length > 0) {
                        initializer.addPackages(true, recursiveClassPackages);
                    }
                    final Class<? extends Extension>[] extensions = loadClasses(loader, config.getProperty("extensions", ""));
                    if (extensions.length > 0) {
                        initializer.addExtensions(extensions);
                    }
                    final Class<?>[] decorators = loadClasses(loader, config.getProperty("decorators", ""));
                    if (decorators.length > 0) {
                        initializer.enableDecorators(decorators);
                    }
                    final Class<?>[] interceptors = loadClasses(loader, config.getProperty("interceptors", ""));
                    if (interceptors.length > 0) {
                        initializer.enableInterceptors(interceptors);
                    }
                    final Class<?>[] alternatives = loadClasses(loader, config.getProperty("alternatives", ""));
                    if (alternatives.length > 0) {
                        initializer.selectAlternatives(alternatives);
                    }
                    final Class<? extends Annotation>[] alternativeStereotypes = loadClasses(loader, config.getProperty("alternativeStereotypes", ""));
                    if (alternativeStereotypes.length > 0) {
                        initializer.selectAlternativeStereotypes(alternativeStereotypes);
                    }
                    if (Boolean.parseBoolean(config.getProperty("disableDiscovery", "false"))) {
                        initializer.disableDiscovery();
                    }
                    ofNullable(config.getProperty("properties")).ifPresent(props -> {
                        final Properties properties = new Properties();
                        try (final Reader reader = new StringReader(props)) {
                            properties.load(reader);
                        } catch (final IOException e) {
                            throw new IllegalStateException(e);
                        }
                        initializer.setProperties(properties.stringPropertyNames().stream().collect(toMap(identity(), properties::getProperty)));
                    });
                    return new Registrations(config.getProperty("configuration.registeredBeans"), config.getProperty("configuration.excludedTypes"));
                })
                .filter(Objects::nonNull)
                .reduce(new Registrations("true", null), (a, b) -> b);
        final SeContainer initialize = initializer.initialize();
        if ("true".equalsIgnoreCase(registrations.includes) || (registrations.includes != null && registrations.includes.contains("*"))) {
            final BeanManager beanManager = initialize.getBeanManager();
            final Collection<String> prefix = "true".equals(registrations.includes) ? null : Stream.of(registrations.includes.split(","))
                    .map(String::trim).filter(it -> !it.isEmpty()).map(v -> v.endsWith("*") ? v.substring(0, v.length() - 1) : v)
                    .collect(toList());
            initialize.getBeanManager().getBeans(Object.class)
                    .forEach(bean -> register(
                            registrations.excludes,
                            bean.getTypes().stream()
                                .filter(it -> {
                                    final boolean isPt = ParameterizedType.class.isInstance(it);
                                    if (isPt) {
                                        final ParameterizedType pt = ParameterizedType.class.cast(it);
                                        return isIncluded(prefix, pt.getRawType());
                                    }
                                    return isIncluded(prefix, it);
                                })
                                .collect(toList()),
                            bean.getQualifiers().stream()
                                    .filter(q -> Default.class != q.annotationType() && Any.class != q.annotationType())
                                    .collect(toList()), type -> {
                                final CreationalContext<Object> creationalContext = beanManager.createCreationalContext(null);
                                final Object reference = beanManager.getReference(bean, type, creationalContext);
                                if (!beanManager.isNormalScope(bean.getScope())) { // not the best but not encouraged too
                                    creationalContext.release();
                                }
                                return reference;
                            }));
        } else if (registrations.includes != null) {
            Stream.of(loadClasses(loader, registrations.includes))
                    .forEach(clazz -> register(registrations.excludes, singletonList(clazz), emptyList(), type -> initialize.select(clazz).get()));
        }
        return initialize;
    }

    private boolean isIncluded(final Collection<String> prefix, final Type it) {
        final String typeName = it.getTypeName();
        return (prefix == null && filterByClass(it)) || (prefix != null && prefix.stream().anyMatch(p -> typeName.startsWith(p)));
    }

    private boolean filterByClass(final Type it) {
        if (it == Principal.class) {
            return false;
        }
        if (it == Instance.class) {
            return false;
        }
        if (it == javax.inject.Provider.class) {
            return false;
        }
        if (it == EventMetadata.class) {
            return false;
        }
        if (it == InjectionPoint.class) {
            return false;
        }
        if (it == Bean.class) {
            return false;
        }
        if (it == Contextual.class) {
            return false;
        }
        if (it == Event.class) {
            return false;
        }
        if (it == Object.class) {
            return false;
        }
        if (it == Conversation.class) {
            return false;
        }
        if (it == Extension.class) {
            return false;
        }
        if (Class.class.isInstance(it)) {
            final Class clazz = Class.class.cast(it);
            return !Modifier.isAbstract(clazz.getModifiers());
        }
        return true;
    }

    private void register(final Collection<String> excludes, final Collection<Type> types,
                          final Collection<Annotation> bindingAnnotations,
                          final Function<Type, Object> provider) {
        types.stream().filter(it -> !bindingAnnotations.isEmpty() || !it.getTypeName().startsWith("java."))
             .filter(it -> excludes == null || excludes.stream().noneMatch(v -> it.getTypeName().startsWith(v)))
             .forEach(type -> {
                final TypeLiteral typeLiteral = TypeLiteral.get(type);
                if (bindingAnnotations.isEmpty()) {
                    bind(typeLiteral).toProvider((Provider<Object>) () -> provider.apply(type));
                } else {
                    bindingAnnotations.forEach(binding ->
                        bind(typeLiteral)
                            .annotatedWith(binding)
                            .toProvider((Provider<Object>) () -> provider.apply(type)));
                }
            });
    }

    private Class[] loadClasses(final ClassLoader loader, final String value) {
        return Stream.of(value.split(","))
                .map(String::trim)
                .filter(it -> !it.isEmpty())
                .map(it -> {
                    try {
                        return loader.loadClass(it);
                    } catch (final ClassNotFoundException e) {
                        throw new IllegalArgumentException(e);
                    }
                })
                .toArray(Class[]::new);
    }

    protected void doClose(final Runnable runnable) {
        runnable.run();
    }

    private static class Registrations {
        private final String includes;
        private final Collection<String> excludes;

        private Registrations(final String includes, final String excludes) {
            this.includes = includes;
            this.excludes = excludes == null ? null : Stream.of(excludes.split(",")).map(String::trim)
                                                            .filter(it -> !it.isEmpty())
                                                            .collect(toSet());
        }
    }
}
