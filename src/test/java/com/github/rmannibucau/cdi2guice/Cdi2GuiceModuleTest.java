package com.github.rmannibucau.cdi2guice;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

import com.google.inject.Guice;
import com.google.inject.Injector;

import org.junit.Test;

import lombok.Getter;

public class Cdi2GuiceModuleTest {
    @Test
    public void run() {
        final Service instance = Guice.createInjector(new Cdi2GuiceModule()).getInstance(Service.class);
        assertTrue(instance.getClass().getName().contains("$$OwbNormalScopeProxy"));
        assertEquals("ok", instance.ok());

        final InjectorAware injectorAware = instance.getInjectorAware();
        assertNotNull(injectorAware);
        final Injector injector = injectorAware.getInjector();
        assertNotNull(injector);
    }

    @ApplicationScoped
    public static class Service {
        @Getter
        private InjectorAware injectorAware;

        protected Service() {
            // no-op
        }

        @Inject
        public Service(final InjectorAware injectorAware) {
            this.injectorAware = injectorAware;
        }

        public String ok() {
            return "ok";
        }
    }

    @ApplicationScoped
    public static class InjectorAware {
        @Inject
        @Getter
        private Injector injector;
    }
}
