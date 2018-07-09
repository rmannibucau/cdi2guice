package com.github.rmannibucau.cdi2guice;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import javax.enterprise.context.ApplicationScoped;

import com.google.inject.Guice;

import org.junit.Test;

public class Cdi2GuiceModuleTest {
    @Test
    public void run() {
        final Service instance = Guice.createInjector(new Cdi2GuiceModule()).getInstance(Service.class);
        assertTrue(instance.getClass().getName().contains("$$OwbNormalScopeProxy"));
        assertEquals("ok", instance.ok());
    }

    @ApplicationScoped
    public static class Service {
        public String ok() {
            return "ok";
        }
    }
}
