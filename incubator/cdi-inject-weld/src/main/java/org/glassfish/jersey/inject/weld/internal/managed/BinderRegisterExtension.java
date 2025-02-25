/*
 * Copyright (c) 2021, 2024 Oracle and/or its affiliates. All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v. 2.0, which is available at
 * http://www.eclipse.org/legal/epl-2.0.
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the
 * Eclipse Public License v. 2.0 are satisfied: GNU General Public License,
 * version 2 with the GNU Classpath Exception, which is available at
 * https://www.gnu.org/software/classpath/license.html.
 *
 * SPDX-License-Identifier: EPL-2.0 OR GPL-2.0 WITH Classpath-exception-2.0
 */

package org.glassfish.jersey.inject.weld.internal.managed;

import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Type;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import javax.annotation.Priority;
import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.context.Dependent;
import javax.enterprise.context.NormalScope;
import javax.enterprise.context.SessionScoped;
import javax.enterprise.inject.spi.AnnotatedType;
import javax.enterprise.inject.spi.BeforeBeanDiscovery;
import javax.enterprise.inject.spi.ProcessAnnotatedType;
import javax.enterprise.inject.spi.Unmanaged;
import javax.enterprise.inject.spi.WithAnnotations;
import javax.inject.Scope;
import javax.inject.Singleton;

import javax.enterprise.event.Observes;
import javax.enterprise.inject.spi.AfterBeanDiscovery;
import javax.enterprise.inject.spi.BeanManager;
import javax.enterprise.inject.spi.Extension;
import javax.enterprise.inject.spi.ProcessInjectionTarget;

import javax.ws.rs.Path;
import javax.ws.rs.RuntimeType;
import javax.ws.rs.container.DynamicFeature;
import javax.ws.rs.core.Feature;
import javax.ws.rs.core.GenericType;
import javax.ws.rs.core.MultivaluedHashMap;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.Application;
import javax.ws.rs.ext.Provider;

import org.glassfish.jersey.inject.weld.internal.data.BindingBeanPair;
import org.glassfish.jersey.inject.weld.internal.inject.InitializableSupplierInstanceBinding;
import org.glassfish.jersey.inject.weld.internal.scope.RequestScopeBean;
import org.glassfish.jersey.inject.weld.internal.inject.InitializableInstanceBinding;
import org.glassfish.jersey.inject.weld.internal.bean.BeanHelper;
import org.glassfish.jersey.inject.weld.internal.injector.ContextInjectionResolverImpl;
import org.glassfish.jersey.inject.weld.internal.injector.JerseyInjectionTarget;
import org.glassfish.jersey.inject.weld.internal.scope.CdiRequestScope;
import org.glassfish.jersey.inject.weld.spi.BootstrapPreinitialization;
import org.glassfish.jersey.internal.BootstrapBag;
import org.glassfish.jersey.internal.ServiceFinder;
import org.glassfish.jersey.internal.inject.AbstractBinder;
import org.glassfish.jersey.internal.inject.Binder;
import org.glassfish.jersey.internal.inject.Binding;
import org.glassfish.jersey.internal.inject.Bindings;
import org.glassfish.jersey.internal.inject.ClassBinding;
import org.glassfish.jersey.internal.inject.CustomAnnotationLiteral;
import org.glassfish.jersey.internal.inject.ForeignDescriptor;
import org.glassfish.jersey.internal.inject.InjectionManager;
import org.glassfish.jersey.internal.inject.InjectionResolver;
import org.glassfish.jersey.internal.inject.InjectionResolverBinding;
import org.glassfish.jersey.internal.inject.InstanceBinding;
import org.glassfish.jersey.internal.inject.Providers;
import org.glassfish.jersey.internal.inject.ServiceHolder;
import org.glassfish.jersey.internal.inject.SupplierClassBinding;
import org.glassfish.jersey.internal.inject.SupplierInstanceBinding;

import org.glassfish.jersey.internal.util.collection.Ref;
import org.glassfish.jersey.internal.util.collection.Refs;
import org.glassfish.jersey.process.internal.RequestScope;
import org.glassfish.jersey.process.internal.RequestScoped;
import org.glassfish.jersey.server.ApplicationHandler;
import org.glassfish.jersey.server.ResourceConfig;
import org.jboss.weld.injection.producer.BasicInjectionTarget;
import org.jboss.weld.injection.producer.BeanInjectionTarget;

/**
 * CDI extension that handles CDI bootstrap events and registers Jersey's internally used components and components registered
 * using {@link Application}.
 */
class BinderRegisterExtension implements Extension {

    private AtomicBoolean registrationDone = new AtomicBoolean(false);
    private Supplier<BeanManager> beanManagerSupplier;
    private Ref<InjectionManager> serverInjectionManager = Refs.emptyRef();

    private BootstrapInjectionManager clientBootstrapInjectionManager = new BootstrapInjectionManager(RuntimeType.CLIENT);
    private WrappingInjectionManager serverBootstrapInjectionManager = new WrappingInjectionManager()
            .setInjectionManager(new BootstrapInjectionManager(RuntimeType.SERVER));
    private BootstrapBag bootstrapBag = new BootstrapBag();

    private final CachingBinder clientBindings = new CachingBinder(serverInjectionManager);
    private final CachingBinder serverBindings = new CachingBinder(serverInjectionManager) {
        @Override
        protected void configure() {
            install(new ContextInjectionResolverImpl.Binder(beanManagerSupplier));
            bind(InitializableInstanceBinding.from(Bindings.service(serverInjectionManager.get()).to(InjectionManager.class)));
        }
    };
    private final CachingBinder annotatedBeansBinder = new CachingBinder(serverInjectionManager);
    private final MergedBindings mergedBindings = new MergedBindings(serverBindings, clientBindings);

    private final List<InitializableInstanceBinding> initializableInstanceBindings = new LinkedList<>();
    private final List<InitializableSupplierInstanceBinding> initializableSupplierInstanceBindings = new LinkedList<>();
    private final MultivaluedMap<Type, BindingBeanPair> supplierClassBindings = new MultivaluedHashMap<>();
    private final MultivaluedMap<Type, BindingBeanPair> classBindings = new MultivaluedHashMap<>();
    private final List<JerseyInjectionTarget> jerseyInjectionTargets = new LinkedList<>();
    private final List<InjectionResolver> injectionResolvers = new LinkedList<>();
    private final Map<Class<?>, Class<? extends Annotation>> annotatedBeans = new HashMap<>();
    private final List<Class<Application>> applications = new LinkedList<>();
    final Set<Class<?>> managedBeans = new HashSet<>();

    /**
     * Ignores the classes which are manually added using bindings (through {@link Application} class) and scanned by CDI.
     * The manual adding is privileged and the beans scanned using CDI are ignored.
     * <p>
     * TODO: The method counts with the condition that the all bindings are known before the CDI scanning has been started,
     * can be changed during the migration from CDI SE to JAVA EE environment.
     *
     * @param pat processed type.
     * @param <T> type of the scanned bean.
     */
    <T> void ignoreManuallyRegisteredComponents(
            @Observes @WithAnnotations({ Path.class, Provider.class }) ProcessAnnotatedType<T> pat) {
        final AnnotatedType<T> annotatedType = pat.getAnnotatedType();
        for (Binding binding : mergedBindings.getBindings()) {
            if (ClassBinding.class.isAssignableFrom(binding.getClass())) {
                ClassBinding<?> classBinding = (ClassBinding<?>) binding;
                if (annotatedType.getJavaClass() == classBinding.getService()) {
                    pat.veto();
                    return;
                }
            } else if (InstanceBinding.class.isAssignableFrom(binding.getClass())) {
                InstanceBinding<?> instanceBinding = (InstanceBinding<?>) binding;
                if (annotatedType.getJavaClass() == instanceBinding.getService().getClass()) {
                    pat.veto();
                    return;
                }
            }
        }
        if (annotatedType.isAnnotationPresent(Path.class)) {
            boolean hasScope = false;
            for (Annotation annotation : annotatedType.getAnnotations()) {
                if (annotation.annotationType().isAnnotationPresent(Scope.class)
                        || annotation.annotationType().isAnnotationPresent(NormalScope.class)) {
                    hasScope = true;
                    break;
                }
            }
            if (!hasScope) {
                annotatedBeans.put(annotatedType.getJavaClass(), javax.enterprise.context.RequestScoped.class);
                pat.configureAnnotatedType().add(javax.enterprise.context.RequestScoped.Literal.INSTANCE);
            }
        }


    }

    <T> void registerJerseyRequestScopedResources(
            @Observes @WithAnnotations(RequestScoped.class) ProcessAnnotatedType<T> pat) {
        if (pat.getAnnotatedType().isAnnotationPresent(RequestScoped.class)
                && !pat.getAnnotatedType().isAnnotationPresent(javax.enterprise.context.RequestScoped.class)) {
            pat.configureAnnotatedType().remove(a -> RequestScoped.class.isInstance(a))
                    .add(javax.enterprise.context.RequestScoped.Literal.INSTANCE);
            annotatedBeans.put(pat.getAnnotatedType().getJavaClass(), javax.enterprise.context.RequestScoped.class);
        }
    }

    void processRegistrars(@Observes BeforeBeanDiscovery beforeBeanDiscovery, BeanManager beanManager) {
        CdiInjectionManagerFactoryBase.setBeanManager(beanManager);
        processRegistrars();
    }

    void handleRequestScoped(
            @Observes @WithAnnotations({javax.enterprise.context.RequestScoped.class}) ProcessAnnotatedType<?> pat) {
        final Class<?> javaClass = pat.getAnnotatedType().getJavaClass();
        if (isJaxrs(javaClass) && isNotJerseyInternal(javaClass)) {
            pat.configureAnnotatedType().add(CustomAnnotationLiteral.INSTANCE);
            annotatedBeans.put(javaClass, javax.enterprise.context.RequestScoped.class);
        }
    }

    <T> void handleApplicationScoped(
            @Observes @WithAnnotations({ApplicationScoped.class}) ProcessAnnotatedType<T> pat) {
        final Class<T> javaClass = pat.getAnnotatedType().getJavaClass();
        if (Application.class.isAssignableFrom(javaClass)) {
            pat.veto();
            applications.add((Class<Application>) javaClass);
        } else if (isJaxrs(javaClass) && isNotJerseyInternal(javaClass)) {
            pat.configureAnnotatedType().add(CustomAnnotationLiteral.INSTANCE);
            annotatedBeans.put(javaClass, ApplicationScoped.class);
        }
    }

    void handleDependent(@Observes @WithAnnotations({Dependent.class}) ProcessAnnotatedType<?> pat) {
        final Class<?> javaClass = pat.getAnnotatedType().getJavaClass();
        if (isJaxrs(javaClass) && isNotJerseyInternal(javaClass)) {
            pat.configureAnnotatedType().add(CustomAnnotationLiteral.INSTANCE);
            annotatedBeans.put(javaClass, Dependent.class);
        }
    }

    void handleSessionScoped(@Observes @WithAnnotations({SessionScoped.class}) ProcessAnnotatedType<?> pat) {
        final Class<?> javaClass = pat.getAnnotatedType().getJavaClass();
        if (isJaxrs(javaClass) && isNotJerseyInternal(javaClass)) {
            pat.configureAnnotatedType().add(CustomAnnotationLiteral.INSTANCE);
            annotatedBeans.put(javaClass, SessionScoped.class);
        }
    }

    void registerSingletonSubResources(@Observes @WithAnnotations({Singleton.class}) ProcessAnnotatedType<?> pat){
        final Class<?> resourceClass = pat.getAnnotatedType().getJavaClass();
        if (resourceClass.getAnnotation(Path.class) != null) {
            annotatedBeans.put(resourceClass, Singleton.class);
        } else if (BeanHelper.isResourceClass(resourceClass)) {
            annotatedBeans.put(resourceClass, Singleton.class);
        }
    }

    void registerJerseyProviders(@Observes @WithAnnotations({Priority.class}) ProcessAnnotatedType<?> pat) {
        final Class<?> javaClass = pat.getAnnotatedType().getJavaClass();
        if (!isNotJerseyInternal(javaClass)) {
            pat.veto(); //veto Jersey internal
        }
        annotatedBeans.put(javaClass, Priority.class);
    }

    /**
     * Wraps all JAX-RS components by Jersey-specific injection target.
     *
     * @param pit process injection target.
     * @param <T> type of the processed injection target.
     */
    public <T> void observeInjectionTarget(@Observes ProcessInjectionTarget<T> pit) {
        if (!BeanInjectionTarget.class.isInstance(pit.getInjectionTarget())) {
            return;
        }
        BasicInjectionTarget<T> it = (BasicInjectionTarget<T>) pit.getInjectionTarget();
        JerseyInjectionTarget<T> jerseyInjectionTarget =
                new JerseyInjectionTarget<>(it, pit.getAnnotatedType().getJavaClass());
        jerseyInjectionTargets.add(jerseyInjectionTarget);
        pit.setInjectionTarget(jerseyInjectionTarget);
    }

    /**
     * Takes all registered bindings and registers them in {@link BeanManager}.
     * <p>
     * Method should register only Jersey internal components and class/instances registered using {@link Application}. Registered
     * classes/instances have priority therefore CDI scanning should veto these classes/instances during {
     *
     * @param abd         {@code AfterBeanDiscovery} event.
     * @param beanManager current {@code BeanManager}.
     * @link ProcessAnnotatedType} bootstrap phase.
     */
    void registerBeans(@Observes AfterBeanDiscovery abd, BeanManager beanManager) {
        serverInjectionManager.set(new CdiInjectionManager(beanManager, mergedBindings));

        beanManagerSupplier = () -> beanManager; // set bean manager supplier to be called by bindings#configure
        CdiInjectionManagerFactoryBase.setBeanManager(beanManager);

        registerApplicationHandler(beanManager);

        registrationDone.set(true); //

        final List<InjectionResolver> contextInjectionResolvers = serverBindings.getBindings().stream()
                .filter(binding -> InjectionResolverBinding.class.isAssignableFrom(binding.getClass()))
                .map(InjectionResolverBinding.class::cast)
                .map(InjectionResolverBinding::getResolver)
                .collect(Collectors.toList());

        injectionResolvers.addAll(contextInjectionResolvers);

        /*
         * Provide registered InjectionResolvers to Jersey's components which has been discovered by CDI in
         * ProcessInjectionTarget bootstrap phase.
         */
        jerseyInjectionTargets.forEach(injectionTarget -> injectionTarget.setInjectionResolvers(injectionResolvers));

        registerBeans(RuntimeType.SERVER, this.serverBindings, abd, beanManager);
        registerBeans(RuntimeType.CLIENT, this.clientBindings, abd, beanManager);

        abd.addBean(new RequestScopeBean(beanManager));

        addAnnotatedBeans(abd, beanManager);

        serverBootstrapInjectionManager.setInjectionManager(serverInjectionManager.get());
        ((CdiInjectionManager) serverInjectionManager.get()).managedBeans = managedBeans;
    }

    private void registerBeans(RuntimeType runtimeType, CachingBinder binder, AfterBeanDiscovery abd,
                               BeanManager beanManager) {
        final Collection<Binding> bindings = binder.getBindings();
        binder.setReadOnly();

        allBindingsLabel:
        for (Binding binding : bindings) {
            if (ClassBinding.class.isAssignableFrom(binding.getClass())) {
                if (RuntimeType.CLIENT == runtimeType) {
                    for (Type contract : ((ClassBinding<?>) binding).getContracts()) {
                        final List<BindingBeanPair> preregistered = classBindings.get(contract);
                        if (preregistered != null && preregistered.size() == 1) {
                            BeanHelper.updateBean(
                                    (ClassBinding<?>) binding, preregistered.get(0), injectionResolvers, beanManager);
                            continue allBindingsLabel;
                        }
                    }
                }
                BindingBeanPair pair = BeanHelper.registerBean(
                        runtimeType, (ClassBinding<?>) binding, abd, injectionResolvers, beanManager);
                for (Type contract : ((ClassBinding<?>) binding).getContracts()) {
                    classBindings.add(contract, pair);
                }
            } else if (SupplierClassBinding.class.isAssignableFrom(binding.getClass())) {
                if (RuntimeType.CLIENT == runtimeType) {
                    for (Type contract : ((SupplierClassBinding<?>) binding).getContracts()) {
                        final List<BindingBeanPair> preregistered = supplierClassBindings.get(contract);
                        if (preregistered != null && preregistered.size() == 1) {
                            BeanHelper.updateSupplierBean(
                                    (SupplierClassBinding<?>) binding, preregistered.get(0), injectionResolvers, beanManager);
                            continue allBindingsLabel;
                        }
                    }
                }
                BindingBeanPair pair = BeanHelper.registerSupplier(
                        runtimeType, (SupplierClassBinding<?>) binding, abd, injectionResolvers, beanManager);
                if (pair != null) {
                    for (Type contract : ((SupplierClassBinding<?>) binding).getContracts()) {
                        supplierClassBindings.add(contract, pair);
                    }
                }
            } else if (InitializableInstanceBinding.class.isAssignableFrom(binding.getClass())) {
                if (RuntimeType.SERVER == runtimeType
                        || !matchInitializableInstanceBinding((InitializableInstanceBinding<?>) binding)) {
                    initializableInstanceBindings.add((InitializableInstanceBinding<?>) binding);
                    BeanHelper.registerBean(
                            runtimeType, (InitializableInstanceBinding<?>) binding, abd, injectionResolvers, beanManager);
                }
            } else if (InitializableSupplierInstanceBinding.class.isInstance(binding)) {
                if (RuntimeType.SERVER == runtimeType
                        || !matchInitializableSupplierInstanceBinding((InitializableSupplierInstanceBinding) binding)) {
                    initializableSupplierInstanceBindings.add((InitializableSupplierInstanceBinding) binding);
                    BeanHelper.registerSupplier(runtimeType, (InitializableSupplierInstanceBinding<?>) binding, abd, beanManager);
                }
            }
        }
    }

    private void addAnnotatedBeans(AfterBeanDiscovery abd, BeanManager beanManager) {
        for (Map.Entry<Class<?>, Class<? extends Annotation>> contract : annotatedBeans.entrySet()) {
            for (Binding binding : serverBindings.getBindings()) {
                if (ClassBinding.class.isInstance(binding)) {
                    if (((ClassBinding) binding).getService() == contract.getClass()) {
                        break;
                    }
                }
                if (InitializableInstanceBinding.class.isInstance(binding)) {
                    if (((InitializableInstanceBinding) binding).getImplementationType() == contract.getClass()) {
                        break;
                    }
                }
            }
            if (isNotJerseyInternal(contract.getKey())) {
                if (beanManager.getBeans(contract.getKey()).isEmpty()) {
                    final ClassBinding<?> binding = bind(contract.getKey(), annotatedBeansBinder);
                    if (Singleton.class.equals(contract.getValue())) {
                        binding.in(Singleton.class);
                    }
                }
                managedBeans.add(contract.getKey()); // add either way
            }
        }

        registerBeans(RuntimeType.SERVER, annotatedBeansBinder, abd, beanManager);
        serverBindings.getBindings().addAll(annotatedBeansBinder.getBindings());
    }

    private void registerApplicationHandler(BeanManager beanManager) {
        final ResourceConfig resourceConfig = new ResourceConfig();

        for (Class<Application> applicationClass : applications) {
            bindApplication(applicationClass, resourceConfig, beanManager);
        }

        new ApplicationHandler(resourceConfig);
    }

    private void bindApplication(Class<Application> applicationClass, ResourceConfig resourceConfig, BeanManager beanManager) {
        final Application application = new Unmanaged<>(applicationClass).newInstance().produce().get();

        for (Class<?> clazz : application.getClasses()) {
            if (beanManager.getBeans(clazz).isEmpty()) {
                // prevent double registration of a class
                // bind(clazz, binder);
                resourceConfig.register(clazz);
            } else if (!annotatedBeans.containsKey(clazz)) {
                annotatedBeans.put(clazz, Provider.class);
            }
        }
        for (Object singleton : application.getSingletons()) {
            final Class<?> clazz = singleton.getClass();
            if (beanManager.getBeans(clazz).isEmpty()) {
                // prevent double registration of a class
//                final InstanceBinding<?> binding = binder.bind(singleton);
//                toSuper(clazz, binding);
                resourceConfig.register(singleton);
            } else if (!annotatedBeans.containsKey(clazz)) {
                annotatedBeans.put(clazz, Provider.class);
            }
        }
    }

//    private void bindApplication(Class<Application> applicationClass, AbstractBinder binder, BeanManager beanManager) {
//        final Application application = new Unmanaged<>(applicationClass).newInstance().produce().get();
//        final CommonConfig commonConfig = new CommonConfig(RuntimeType.SERVER, ComponentBag.INCLUDE_ALL);
//
//        for (Class<?> clazz : application.getClasses()) {
//            if (beanManager.getBeans(clazz).isEmpty()) {
//                // prevent double registration of a class
//                // bind(clazz, binder);
//                commonConfig.register(clazz);
//            } else if (!annotatedBeans.containsKey(clazz)) {
//                annotatedBeans.put(clazz, Provider.class);
//            }
//        }
//        for (Object singleton : application.getSingletons()) {
//            final Class<?> clazz = singleton.getClass();
//            if (beanManager.getBeans(clazz).isEmpty()) {
//                // prevent double registration of a class
////                final InstanceBinding<?> binding = binder.bind(singleton);
////                toSuper(clazz, binding);
//                commonConfig.register(singleton);
//            } else if (!annotatedBeans.containsKey(clazz)) {
//                annotatedBeans.put(clazz, Provider.class);
//            }
//        }
//    }

    private static <T> ClassBinding<T> bind(Class<T> clazz, AbstractBinder binder) {
        final ClassBinding<T> binding = binder.bindAsContract(clazz);
        return toSuper(clazz, binding);
    }

    private static <T extends Binding> T toSuper(Class<?> clazz, T binding) {
        Class<?> superClass = clazz;
        while (superClass != null) {
            superClass = superClass.getSuperclass();
            if (superClass != null) {
                binding.to(superClass);
            }
        }
        for (Class<?> intf : clazz.getInterfaces()){
            binding.to(intf);
        }
        return binding;
    }

//    // Check first if a class is a JAX-RS resource, and only if so check with validation.
//    // This prevents unnecessary warnings being logged for pure CDI beans.
//    private final Cache<Class<?>, Boolean> jaxRsResourceCache = new Cache<>(
//            clazz -> Resource.from(clazz, true) != null && Resource.from(clazz) != null);
//
//    public boolean isJaxRsResource(Class<?> resource) {
//        return jaxRsResourceCache.apply(resource);
//    }

    private boolean isJaxrs(Class<?> clazz) {
        return Providers.isJaxRsProvider(clazz) || BeanHelper.isResourceClass(clazz) || isJerseyRegistrable(clazz);
    }

    private boolean isJerseyRegistrable(Class<?> clazz) {
        return Feature.class.isAssignableFrom(clazz) || DynamicFeature.class.isAssignableFrom(clazz);
    }

    private boolean isNotJerseyInternal(Class<?> clazz) {
        final Package pkg = clazz.getPackage();
        if (pkg == null) { // Class.getPackage() could return null
            return false;
        }

        final String pkgName = pkg.getName();
        return !pkgName.startsWith("org.glassfish.jersey")
                || pkgName.startsWith("org.glassfish.jersey.examples")
                || pkgName.startsWith("org.glassfish.jersey.tests");
    }

    private boolean matchInitializableInstanceBinding(InitializableInstanceBinding candidate) {
        for (InitializableInstanceBinding iib : initializableInstanceBindings) {
            if (iib.matches(candidate).matches()) {
                return true;
            }
        }
        return false;
    }

    private boolean matchInitializableSupplierInstanceBinding(InitializableSupplierInstanceBinding candidate) {
        for (InitializableSupplierInstanceBinding isib : initializableSupplierInstanceBindings) {
            if (isib.matches(candidate).matches()) {
                return true;
            }
        }
        return false;
    }


    /** To be used by the tests only */
    public void register(BeforeBeanDiscovery beforeBeanDiscovery, Binding binding) {
        register(RuntimeType.SERVER, binding);
    }

    /** To be used by the tests only */
    public void register(BeforeBeanDiscovery beforeBeanDiscovery, Iterable<Binding> bindings) {
        register(RuntimeType.SERVER, bindings);
    }

    private void register(RuntimeType runtimeType, Binding binding) {
        final AbstractBinder bindings = runtimeType == RuntimeType.CLIENT ? clientBindings : serverBindings;
        if (InstanceBinding.class.isInstance(binding)) {
            bindings.bind(InitializableInstanceBinding.from((InstanceBinding) binding));
        } else if (SupplierInstanceBinding.class.isInstance(binding)) {
            bindings.bind(InitializableSupplierInstanceBinding.from((SupplierInstanceBinding) binding));
        } else {
            bindings.bind(binding);
        }
    }

    private void register(RuntimeType runtimeType, Iterable<Binding> bindings) {
        for (Binding binding : bindings) {
            register(runtimeType, binding);
        }
    }

    private void processRegistrars() {
        final List<BootstrapPreinitialization> registrars = new LinkedList<>();
        for (BootstrapPreinitialization registrar : ServiceFinder.find(BootstrapPreinitialization.class)) {
            registrars.add(registrar);
        }
        for (BootstrapPreinitialization registrar : registrars) {
            registrar.register(RuntimeType.SERVER, serverBindings);
        }

        for (BootstrapPreinitialization registrar : registrars) {
            registrar.register(RuntimeType.CLIENT, clientBindings);
        }
    }

    InjectionManager getInjectionManager(RuntimeType runtimeType) {
        if (RuntimeType.CLIENT == runtimeType) {
            return registrationDone.get()
                    ? new CdiClientInjectionManager(beanManagerSupplier.get(), mergedBindings)
                    : clientBootstrapInjectionManager;
        } else {
            return registrationDone.get() ? serverInjectionManager.get() : serverBootstrapInjectionManager;
        }
    }

    /**
     * Injection manager used during the bootstrap. It is used to create the actual beans in the beans manager.
     * Other InjectionManagers sets the beans (beans binding) by a value provided in runtime.
     */
    private class BootstrapInjectionManager implements InjectionManager {

        private final RuntimeType runtimeType;

        private BootstrapInjectionManager(RuntimeType runtimeType) {
            this.runtimeType = runtimeType;
        }

        @Override
        public void completeRegistration() {
            //noop
        }

        @Override
        public void shutdown() {
            //noop
        }

        @Override
        public boolean isShutdown() {
            return false;
        }

        @Override
        public void register(Binding binding) {
            BinderRegisterExtension.this.register(runtimeType, binding);
        }

        @Override
        public void register(Iterable<Binding> descriptors) {
           for (Binding binding : descriptors) {
               register(binding);
           }
        }

        @Override
        public void register(Binder binder) {
            register(binder.getBindings());
        }

        @Override
        public void register(Object provider) throws IllegalArgumentException {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isRegistrable(Class<?> clazz) {
            return false;
        }

        @Override
        public <T> T createAndInitialize(Class<T> createMe) {
            if (RequestScope.class == createMe) {
                return (T) new CdiRequestScope();
            }
            if (isNotJerseyInternal(createMe)) {
                return null;
            }
            try {
                Constructor<T> constructor = createMe.getConstructor();
                return constructor.newInstance();
            } catch (NoSuchMethodException | IllegalAccessException | InstantiationException | InvocationTargetException e) {
                return null;
            }
        }

        @Override
        public <T> T create(Class<T> createMe) {
            return createAndInitialize(createMe);
        }

        @Override
        public <T> List<ServiceHolder<T>> getAllServiceHolders(Class<T> contractOrImpl, Annotation... qualifiers) {
            return Collections.EMPTY_LIST;
        }

        @Override
        public <T> T getInstance(Class<T> contractOrImpl, Annotation... qualifiers) {
            return getInstance(contractOrImpl);
        }

        @Override
        public <T> T getInstance(Class<T> contractOrImpl, String classAnalyzer) {
            return getInstance(contractOrImpl);
        }

        @Override
        public <T> T getInstance(Class<T> contractOrImpl) {
            return createAndInitialize(contractOrImpl);
        }

        @Override
        public <T> T getInstance(Type contractOrImpl) {
            return (T) createAndInitialize((Class) contractOrImpl);
        }

        @Override
        public Object getInstance(ForeignDescriptor foreignDescriptor) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ForeignDescriptor createForeignDescriptor(Binding binding) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> List<T> getAllInstances(Type contractOrImpl) {
            final T t = getInstance(contractOrImpl);
            return t != null ? Collections.singletonList(t) : Collections.EMPTY_LIST;
        }

        @Override
        public void inject(Object injectMe) {
           // noop;
        }

        @Override
        public void inject(Object injectMe, String classAnalyzer) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void preDestroy(Object preDestroyMe) {
            //noop
        }
    }

    /**
     * AbstractBinder that supports calling {@link #getBindings()} multiple times by caching the result.
     * Each additional binding is added to the cache by the next call of {@link #getBindings()}.
     * When {@link #setReadOnly()} is called, no additional binding is added to the cache.
     */
    private class CachingBinder extends AbstractBinder {
        private final Ref<InjectionManager> injectionManager;
        private AbstractBinder temporaryBinder = new TemporaryBinder();
        private final Collection<Binding> bindings = new LinkedList<>();

        private CachingBinder(Ref<InjectionManager> injectionManager) {
            this.injectionManager = injectionManager;
        }

        @Override
        protected void configure() {
            // noop
        }

        @Override
        public <T> ClassBinding<T> bind(Class<T> serviceType) {
            return temporaryBinder.bind(serviceType);
        }

        @Override
        public Binding bind(Binding binding) {
            return temporaryBinder.bind(binding);
        }

        @Override
        public <T> ClassBinding<T> bindAsContract(GenericType<T> serviceType) {
            return temporaryBinder.bindAsContract(serviceType);
        }

        @Override
        public <T> ClassBinding<T> bindAsContract(Class<T> serviceType) {
            return temporaryBinder.bindAsContract(serviceType);
        }

        @Override
        public ClassBinding<Object> bindAsContract(Type serviceType) {
            return temporaryBinder.bindAsContract(serviceType);
        }

        @Override
        public <T> InstanceBinding<T> bind(T service) {
            return temporaryBinder.bind(service);
        }

        @Override
        public <T> SupplierClassBinding<T> bindFactory(
                Class<? extends Supplier<T>> supplierType, Class<? extends Annotation> supplierScope) {
            return temporaryBinder.bindFactory(supplierType, supplierScope);
        }

        @Override
        public <T> SupplierClassBinding<T> bindFactory(Class<? extends Supplier<T>> supplierType) {
            return temporaryBinder.bindFactory(supplierType);
        }

        @Override
        public <T> SupplierInstanceBinding<T> bindFactory(Supplier<T> factory) {
            return temporaryBinder.bindFactory(factory);
        }

        @Override
        public <T extends InjectionResolver> InjectionResolverBinding<T> bind(T resolver) {
            return temporaryBinder.bind(resolver);
        }

        @Override
        public Collection<Binding> getBindings() {
            if (!readOnly) {
                if (registrationDone.get()) {
                    bindings.addAll(super.getBindings());
                }
                final Collection<Binding> newBindings = temporaryBinder.getBindings();
                for (Binding binding : newBindings) {
                    if (InstanceBinding.class.isAssignableFrom(binding.getClass())) {
                        binding = InitializableInstanceBinding.from((InstanceBinding) binding);
                    } else if (SupplierInstanceBinding.class.isAssignableFrom(binding.getClass())) {
                        binding = InitializableSupplierInstanceBinding.from((SupplierInstanceBinding) binding);
                    }
                    bindings.add(binding);
                }
                temporaryBinder = new TemporaryBinder();
            }
            return bindings;
        }

        private boolean readOnly = false;

        void setReadOnly() {
            readOnly = true;
        }

        private class TemporaryBinder extends AbstractBinder {

            @Override
            protected void configure() {
                // do nothing
            }
        }
    }

    private static class MergedBindings implements Binder {
        private final AbstractBinder first;
        private final AbstractBinder second;


        private MergedBindings(AbstractBinder first, AbstractBinder second) {
            this.first = first;
            this.second = second;
        }

        public Collection<Binding> getBindings() {
            final Collection<Binding> firstBindings = first.getBindings();
            final Collection<Binding> secondBindings = second.getBindings();

            Collection<Binding> merged = new Collection<Binding>() {
                @Override
                public int size() {
                    return firstBindings.size() + secondBindings.size();
                }

                @Override
                public boolean isEmpty() {
                    return firstBindings.isEmpty() && secondBindings.isEmpty();
                }

                @Override
                public boolean contains(Object o) {
                    return firstBindings.contains(o) || secondBindings.contains(o);
                }

                @Override
                public Iterator<Binding> iterator() {
                    final Iterator<Binding> firstIterator = firstBindings.iterator();
                    final Iterator<Binding> secondIterator = secondBindings.iterator();
                    return new Iterator<Binding>() {
                        @Override
                        public boolean hasNext() {
                            return firstIterator.hasNext() || secondIterator.hasNext();
                        }

                        @Override
                        public Binding next() {
                            return firstIterator.hasNext() ? firstIterator.next() : secondIterator.next();
                        }
                    };
                }

                // Used by IDE while debugging
                @Override
                public Object[] toArray() {
                    Object[] array = new Object[size()];
                    final Iterator<Binding> bindingIterator = iterator();
                    int i = 0;
                    while (bindingIterator.hasNext()) {
                        array[i++] = bindingIterator.next();
                    }
                    return array;
                }

                @Override
                public <T> T[] toArray(T[] a) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public boolean add(Binding binding) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public boolean remove(Object o) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public boolean containsAll(Collection<?> c) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public boolean addAll(Collection<? extends Binding> c) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public boolean removeAll(Collection<?> c) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public boolean retainAll(Collection<?> c) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public void clear() {
                    throw new UnsupportedOperationException();
                }
            };
            return merged;
        }
    }
}
