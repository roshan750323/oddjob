package org.oddjob.remote;

import org.oddjob.arooa.utils.ClassUtils;

import java.util.*;

/**
 * Builder for {@link NotificationInfo}.
 */
public class NotificationInfoBuilder {

    private final Map<String, Class<?>> classes = new HashMap<>();
    private final Map<String, String> descriptions = new HashMap<>();

    public OfClass addType(String type) {

        return new OfClass(type);
    }

    public class OfClass {

        private final String type;

        public OfClass(String type) {
            Objects.requireNonNull(type);
            this.type = type;
        }

        public Options ofClass(Class<?> cl) {

            return new Options(type, cl);
        }
    }

    public class Options {

        private final String type;

        private final Class<?> cl;

        private String description;

        public Options(String type, Class<?> cl) {
            this.type = type;
            this.cl = cl;
        }

        public NotificationInfo build() {

            return new Impl(and());
        }

        public Options withDescription(String description) {
            this.description = description;
            return this;
        }

        public NotificationInfoBuilder and() {
            classes.put(type,cl);
            Optional.ofNullable(description).ifPresent(d -> descriptions.put(type, d));
            return NotificationInfoBuilder.this;
        }
    }

    public static FromItems fromItems(List<NotificationInfoItem> items) {

        return new NotificationInfoBuilder().new FromItems(items);
    }

    public class FromItems {

        private final List<NotificationInfoItem> items;

        private ClassLoader classLoader;


        public FromItems(List<NotificationInfoItem> items) {
            this.items = Objects.requireNonNull(items);
        }

        public FromItems withClassLoader(ClassLoader classLoader) {

            this.classLoader = classLoader;
            return this;
        }

        public NotificationInfo build() {

            ClassLoader classLoader = Optional.ofNullable(this.classLoader)
                    .orElse(getClass().getClassLoader());

            items.forEach(item -> {
                Class<?> dataType;
                try {
                    dataType = ClassUtils.classFor(item.getOfType(), classLoader);
                }
                catch (ClassNotFoundException e) {
                    throw new RuntimeException(e);
                }

                classes.put(item.getType(), dataType);
            });

            return new Impl(NotificationInfoBuilder.this);
        }
    }

    private static class Impl implements NotificationInfo {

        private final Map<String, Class<?>> classes;
        private final Map<String, String> descriptions;

        Impl(NotificationInfoBuilder builder) {
            classes = new HashMap<>(builder.classes);
            descriptions = new HashMap<>(builder.descriptions);
        }

        @Override
        public Set<String> getTypes() {
            return classes.keySet();
        }

        @Override
        public Class<?> getTypeOf(String type) {
            return classes.get(type);
        }

        @Override
        public String getDescription(String type) {
            return descriptions.get(type);
        }
    }
}
