package net.bytebuddy.build;

import net.bytebuddy.ByteBuddy;
import net.bytebuddy.ClassFileVersion;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.dynamic.scaffold.inline.MethodNameTransformer;
import net.bytebuddy.implementation.LoadedTypeInitializer;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.pool.TypePool;

import java.io.Closeable;
import java.io.IOException;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * A plugin that allows for the application of Byte Buddy transformations during a build process. This plugin's
 * transformation is applied to any type matching this plugin's type matcher. Plugin types must be public,
 * non-abstract and must declare a public default constructor to work.
 */
public interface Plugin extends ElementMatcher<TypeDescription> {

    /**
     * Applies this plugin.
     *
     * @param builder          The builder to use as a basis for the applied transformation.
     * @param typeDescription  The type being transformed.
     * @param classFileLocator A class file locator that can locate other types in the scope of the project.
     * @return The supplied builder with additional transformations registered.
     */
    DynamicType.Builder<?> apply(DynamicType.Builder<?> builder, TypeDescription typeDescription, ClassFileLocator classFileLocator);

    /**
     * A non-operational plugin that does not instrument any type.
     */
    @HashCodeAndEqualsPlugin.Enhance
    class NoOp implements Plugin {

        @Override
        public DynamicType.Builder<?> apply(DynamicType.Builder<?> builder, TypeDescription typeDescription, ClassFileLocator classFileLocator) {
            throw new IllegalStateException("Cannot apply non-operational plugin");
        }

        @Override
        public boolean matches(TypeDescription target) {
            return false;
        }
    }

    /**
     * An abstract base for a {@link Plugin} that matches types by a given {@link ElementMatcher}.
     */
    @HashCodeAndEqualsPlugin.Enhance
    abstract class ForElementMatcher implements Plugin {

        /**
         * The element matcher to apply.
         */
        private final ElementMatcher<? super TypeDescription> matcher;

        /**
         * Creates a new plugin that matches types using an element matcher.
         *
         * @param matcher The element matcher to apply.
         */
        protected ForElementMatcher(ElementMatcher<? super TypeDescription> matcher) {
            this.matcher = matcher;
        }

        @Override
        public boolean matches(TypeDescription target) {
            return matcher.matches(target);
        }
    }

    /**
     * A compound plugin that applies several plugins in a row.
     */
    @HashCodeAndEqualsPlugin.Enhance
    class Compound implements Plugin {

        /**
         * The plugins to apply.
         */
        private final List<Plugin> plugins;

        /**
         * Creates a compound plugin.
         *
         * @param plugin The plugins to apply.
         */
        public Compound(Plugin... plugin) {
            this(Arrays.asList(plugin));
        }

        /**
         * Creates a compound plugin.
         *
         * @param plugins The plugins to apply.
         */
        public Compound(List<? extends Plugin> plugins) {
            this.plugins = new ArrayList<Plugin>();
            for (Plugin plugin : plugins) {
                if (plugin instanceof Compound) {
                    this.plugins.addAll(((Compound) plugin).plugins);
                } else if (!(plugin instanceof NoOp)) {
                    this.plugins.add(plugin);
                }
            }
        }

        @Override
        public DynamicType.Builder<?> apply(DynamicType.Builder<?> builder, TypeDescription typeDescription, ClassFileLocator classFileLocator) {
            for (Plugin plugin : plugins) {
                if (plugin.matches(typeDescription)) {
                    builder = plugin.apply(builder, typeDescription, classFileLocator);
                }
            }
            return builder;
        }

        @Override
        public boolean matches(TypeDescription target) {
            for (Plugin plugin : plugins) {
                if (plugin.matches(target)) {
                    return true;
                }
            }
            return false;
        }
    }

    class Engine {

        ClassFileVersion classFileVersion;

        EntryPoint entryPoint;

        MethodNameTransformer methodNameTransformer;

        ClassFileLocator classFileLocator;

        TypePool typePool;

        List<Plugin> plugins;

        boolean failFast;

        boolean failOnLiveInitializer;

        public void apply(Source source) throws IOException {
            apply(source, Listener.NoOp.INSTANCE);
        }

        public void apply(Source source, Listener listener) throws IOException {
            ByteBuddy byteBuddy = entryPoint.byteBuddy(classFileVersion);
            for (Source.Element element : source) {
                TypePool.Resolution resolution = typePool.describe(element.getName());
                boolean transformed = false, failed = false;
                if (resolution.isResolved()) {
                    TypeDescription typeDescription = resolution.resolve();
                    DynamicType.Builder<?> builder = entryPoint.transform(typeDescription, byteBuddy, classFileLocator, methodNameTransformer);
                    for (Plugin plugin : plugins) {
                        try {
                            if (plugin.matches(typeDescription)) {
                                builder = plugin.apply(builder, typeDescription, classFileLocator);
                                transformed = true;
                            }
                        } catch (RuntimeException exception) {
                            if (failFast) {
                                throw exception;
                            } else {
                                listener.onFailure(typeDescription, exception);
                                failed = true;
                            }
                        }
                    }
                    if (failed) {
                        listener.onFailure(typeDescription);
                    } else if (transformed) {
                        DynamicType dynamicType = builder.make();
                        for (Map.Entry<TypeDescription, LoadedTypeInitializer> entry : dynamicType.getLoadedTypeInitializers().entrySet()) {
                            if (failOnLiveInitializer && entry.getValue().isAlive()) {
                                throw new IllegalStateException("Cannot apply live initializer for " + entry.getKey());
                            }
                        }
                        element.replace(dynamicType);
                        listener.onSuccess(typeDescription);
                    } else {
                        element.retain();
                        listener.onIgnore(typeDescription);
                    }
                } else {
                    listener.onUnresolved(element.getName());
                }
            }
        }

        interface Source extends Iterable<Source.Element>, Closeable {

            interface Element {

                String getName();

                void retain();

                void replace(DynamicType dynamicType) throws IOException;
            }

            class ForJarFile implements Source {

                private final JarFile jarFile;

                public ForJarFile(JarFile jarFile) {
                    this.jarFile = jarFile;
                }

                @Override
                public Iterator<Element> iterator() {
                    return new JarFileIterator(jarFile.entries());
                }

                @Override
                public void close() throws IOException {

                }

                protected class JarFileIterator implements Iterator<Element> {

                    private final Enumeration<JarEntry> enumeration;

                    protected JarFileIterator(Enumeration<JarEntry> enumeration) {
                        this.enumeration = enumeration;
                    }

                    @Override
                    public boolean hasNext() {
                        return enumeration.hasMoreElements();
                    }

                    @Override
                    public Element next() {
                        return new JarFileElement(enumeration.nextElement());
                    }

                    @Override
                    public void remove() {

                    }

                    protected class JarFileElement implements Element {

                        private final JarEntry entry;

                        protected JarFileElement(JarEntry entry) {
                            this.entry = entry;
                        }

                        @Override
                        public String getName() {
                            return entry.getName();
                        }

                        @Override
                        public void retain() {

                        }

                        @Override
                        public void replace(DynamicType dynamicType) throws IOException {

                        }
                    }
                }
            }
        }

        interface Listener {

            void onSuccess(TypeDescription typeDescription);

            void onIgnore(TypeDescription typeDescription);

            void onFailure(TypeDescription typeDescription, Throwable throwable);

            void onFailure(TypeDescription typeDescription);

            void onUnresolved(String name);

            enum NoOp implements Listener {

                INSTANCE;

                @Override
                public void onSuccess(TypeDescription typeDescription) {
                    /* do nothing */
                }

                @Override
                public void onIgnore(TypeDescription typeDescription) {
                    /* do nothing */
                }

                @Override
                public void onFailure(TypeDescription typeDescription, Throwable throwable) {
                    /* do nothing */
                }

                @Override
                public void onFailure(TypeDescription typeDescription) {
                    /* do nothing */
                }

                @Override
                public void onUnresolved(String name) {
                    /* do nothing */
                }
            }
        }
    }
}
