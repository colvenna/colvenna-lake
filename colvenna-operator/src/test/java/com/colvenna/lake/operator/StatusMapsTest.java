package com.colvenna.lake.operator;

import static org.assertj.core.api.Assertions.assertThat;

import com.colvenna.lake.operator.crd.LakeDashboard;
import com.colvenna.lake.operator.crd.LakeDashboardStatus;
import com.colvenna.lake.operator.crd.LakeTaskStatus;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.fabric8.kubernetes.client.CustomResource;
import io.fabric8.kubernetes.client.utils.KubernetesSerialization;
import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Server-side apply turns a status map applied empty into null, which the CRD schema refuses (the
 * whole status patch fails): an empty map is left out instead, and apply removes it.
 */
class StatusMapsTest {
    @Test
    void everyStatusMapIsLeftOutWhenEmpty() throws Exception {
        List<String> unmarked = new ArrayList<>();
        Set<Class<?>> seen = new HashSet<>();
        List<Class<?>> statuses = statuses();
        assertThat(statuses).contains(LakeDashboardStatus.class, LakeTaskStatus.class);
        for (Class<?> status : statuses) {
            walk(status, seen, unmarked);
        }
        assertThat(unmarked).isEmpty();
    }

    /** The status types of every custom resource in the crd package. */
    private static List<Class<?>> statuses() throws Exception {
        String pkg = LakeDashboard.class.getPackageName();
        Path dir =
                Path.of(LakeDashboard.class.getResource("LakeDashboard.class").toURI()).getParent();
        List<Class<?>> out = new ArrayList<>();
        try (var files = Files.list(dir)) {
            for (Path f : files.sorted().toList()) {
                String name = f.getFileName().toString();
                if (!name.endsWith(".class") || name.contains("$")) {
                    continue;
                }
                Class<?> c = Class.forName(pkg + "." + name.substring(0, name.length() - 6));
                if (CustomResource.class.isAssignableFrom(c)
                        && c.getGenericSuperclass() instanceof ParameterizedType p) {
                    out.add((Class<?>) p.getActualTypeArguments()[1]);
                }
            }
        }
        return out;
    }

    @Test
    void anEmptiedDashboardUsersMapIsNotSent() {
        LakeDashboard d = new LakeDashboard();
        d.getMetadata().setName("d");
        d.setStatus(new LakeDashboardStatus());
        d.getStatus().phase = "Failed";
        assertThat(new KubernetesSerialization().asJson(d)).doesNotContain("users");
    }

    private static void walk(Class<?> type, Set<Class<?>> seen, List<String> unmarked) {
        if (!seen.add(type) || !type.getName().startsWith("com.colvenna.lake.")) {
            return;
        }
        for (Field f : type.getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                continue;
            }
            if (Map.class.isAssignableFrom(f.getType())) {
                JsonInclude include = f.getAnnotation(JsonInclude.class);
                if (include == null || include.value() != JsonInclude.Include.NON_EMPTY) {
                    unmarked.add(type.getSimpleName() + "." + f.getName());
                }
            }
            walk(f.getType(), seen, unmarked);
            if (f.getGenericType() instanceof ParameterizedType p) {
                for (Type arg : p.getActualTypeArguments()) {
                    if (arg instanceof Class<?> c) {
                        walk(c, seen, unmarked);
                    }
                }
            }
        }
    }
}
