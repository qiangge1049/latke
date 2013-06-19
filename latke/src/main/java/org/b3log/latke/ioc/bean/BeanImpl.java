/*
 * Copyright (c) 2009, 2010, 2011, 2012, 2013, B3log Team
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.b3log.latke.ioc.bean;


import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.enterprise.context.spi.CreationalContext;
import javax.enterprise.inject.spi.AnnotatedConstructor;
import javax.enterprise.inject.spi.AnnotatedField;
import javax.enterprise.inject.spi.AnnotatedMethod;
import javax.enterprise.inject.spi.AnnotatedParameter;
import javax.enterprise.inject.spi.AnnotatedType;
import javax.enterprise.inject.spi.BeanManager;
import javax.enterprise.inject.spi.InjectionPoint;
import javax.inject.Named;
import javax.inject.Provider;
import org.b3log.latke.ioc.LatkeBeanManager;
import org.b3log.latke.ioc.annotated.AnnotatedTypeImpl;
import org.b3log.latke.ioc.config.Configurator;
import org.b3log.latke.ioc.literal.NamedLiteral;
import org.b3log.latke.ioc.point.FieldInjectionPoint;
import org.b3log.latke.ioc.point.ParameterInjectionPoint;
import org.b3log.latke.ioc.provider.FieldProvider;
import org.b3log.latke.ioc.provider.ParameterProvider;
import org.b3log.latke.logging.Level;
import org.b3log.latke.logging.Logger;


/**
 * Latke bean implementation.
 *
 * @param <T> the declaring type
 * @author <a href="mailto:DL88250@gmail.com">Liang Ding</a>
 * @version 1.0.0.7, Mar 30, 2010
 */
public class BeanImpl<T> implements LatkeBean<T> {

    /**
     * Logger.
     */
    private static final Logger LOGGER = Logger.getLogger(BeanImpl.class.getName());

    /**
     * Bean manager.
     */
    private LatkeBeanManager beanManager;

    /**
     * Bean configurator.
     */
    private Configurator configurator;

    /**
     * Bean name.
     */
    private String name;

    /**
     * Bean scope.
     */
    private Class<? extends Annotation> scope;

    /**
     * Bean qualifiers.
     */
    private Set<Annotation> qualifiers;

    /**
     * Bean class.
     */
    private Class<T> beanClass;

    /**
     * Bean types.
     */
    private Set<Type> types;

    /**
     * Annotated type of this bean.
     */
    private AnnotatedType<T> annotatedType;

    /**
     * Dependency resolver.
     */
    private Resolver resolver;

    /**
     * Field injection points.
     */
    private Set<FieldInjectionPoint> fieldInjectionPoints;

    /**
     * Constructor parameter injection points.
     */
    private Map<AnnotatedConstructor<T>, List<ParameterInjectionPoint>> constructorParameterInjectionPoints;

    /**
     * Method parameter injection points.
     */
    private Map<AnnotatedMethod<?>, List<ParameterInjectionPoint>> methodParameterInjectionPoints;

    /**
     * Constructor parameter providers.
     */
    private List<ParameterProvider<?>> constructorParameterProviders;

    /**
     * Field provider.
     */
    private Set<FieldProvider<?>> fieldProviders;

    /**
     * Method parameter providers.
     */
    private Map<AnnotatedMethod<?>, List<ParameterProvider<?>>> methodParameterProviders;

    /**
     * Constructs a Latke bean.
     * 
     * @param beanManager the specified bean manager
     * @param name the specified bean name
     * @param scope the specified bean scope
     * @param qualifiers the specified bean qualifiers
     * @param beanClass the specified bean class
     * @param types the specified bean types
     */
    public BeanImpl(final LatkeBeanManager beanManager, final String name, final Class<? extends Annotation> scope,
        final Set<Annotation> qualifiers, final Class<T> beanClass, final Set<Type> types) {
        this.beanManager = beanManager;
        this.name = name;
        this.scope = scope;
        this.qualifiers = qualifiers;
        this.beanClass = beanClass;
        this.types = types;

        this.configurator = beanManager.getConfigurator();
        annotatedType = new AnnotatedTypeImpl<T>(beanClass);
        resolver = new ResolverImpl();
        constructorParameterInjectionPoints = new HashMap<AnnotatedConstructor<T>, List<ParameterInjectionPoint>>();
        constructorParameterProviders = new ArrayList<ParameterProvider<?>>();
        methodParameterInjectionPoints = new HashMap<AnnotatedMethod<?>, List<ParameterInjectionPoint>>();
        methodParameterProviders = new HashMap<AnnotatedMethod<?>, List<ParameterProvider<?>>>();
        fieldInjectionPoints = new HashSet<FieldInjectionPoint>();
        fieldProviders = new HashSet<FieldProvider<?>>();

        initFieldInjectionPoints();
        initConstructorInjectionPoints();
        initMethodInjectionPoints();
    }

    /**
     * Resolves dependencies for the specified reference.
     * 
     * @param reference the specified reference
     * @throws Exception exception
     */
    private void resolveDependencies(final Object reference) throws Exception {
        final Class<?> superclass = reference.getClass().getSuperclass();

        resolveSuperclassFieldDependencies(reference, superclass);
        resolveSuperclassMethodDependencies(reference, superclass);
        resolveCurrentclassFieldDependencies(reference);
        resolveCurrentclassMethodDependencies(reference);
    }

    /**
     * Constructs the bean object with dependencies resolved.
     * 
     * @return bean object
     * @throws Exception exception
     */
    private T instantiateReference() throws Exception {
        T ret = null;

        if (constructorParameterInjectionPoints.size() == 1) {
            // only one constructor allow to be annotated with @Inject
            // instantiate an instance by the constructor annotated with @Inject
            final AnnotatedConstructor<T> annotatedConstructor = constructorParameterInjectionPoints.keySet().iterator().next();
            final List<ParameterInjectionPoint> paraInjectionPoints = constructorParameterInjectionPoints.get(annotatedConstructor);
            final Object[] args = new Object[paraInjectionPoints.size()];
            int i = 0;

            for (final ParameterInjectionPoint paraInjectionPoint : paraInjectionPoints) {
                Object arg = beanManager.getInjectableReference(paraInjectionPoint, null);

                if (arg == null) {
                    for (final ParameterProvider<?> provider : constructorParameterProviders) {
                        if (provider.getAnnotated().equals(paraInjectionPoint.getAnnotated())) {
                            arg = provider;
                            break;
                        }
                    }
                }

                args[i++] = arg;
            }

            ret = annotatedConstructor.getJavaMember().newInstance(args);
        } else {
            ret = beanClass.newInstance();
        }

        // if (isInterceptionEnabled) {
        // final BeanInterceptor interceptor = new BeanInterceptor(ret, this);
        // ret = wrap(ret, interceptor);
        // }

        return ret;
    }

    /**
     * Resolves current class field dependencies for the specified reference.
     * 
     * @param reference the specified reference
     */
    private void resolveCurrentclassFieldDependencies(final Object reference) {
        for (final FieldInjectionPoint injectionPoint : fieldInjectionPoints) {
            Object injection = beanManager.getInjectableReference(injectionPoint, null);

            if (injection == null) {
                for (final FieldProvider<?> provider : fieldProviders) {
                    if (provider.getAnnotated().equals(injectionPoint.getAnnotated())) {
                        injection = provider;
                        break;
                    }
                }
            }

            resolver.resolveField(injectionPoint.getAnnotated(), reference, injection);
        }
    }

    /**
     * Resolves current class method dependencies for the specified reference.
     * 
     * @param reference the specified reference
     */
    private void resolveCurrentclassMethodDependencies(final Object reference) {
        for (final Map.Entry<AnnotatedMethod<?>, List<ParameterInjectionPoint>> methodParameterInjectionPoint
            : methodParameterInjectionPoints.entrySet()) {
            final List<ParameterInjectionPoint> paraSet = methodParameterInjectionPoint.getValue();
            final Object[] args = new Object[paraSet.size()];
            int i = 0;

            for (final ParameterInjectionPoint paraInjectionPoint : paraSet) {
                Object arg = beanManager.getInjectableReference(paraInjectionPoint, null);

                if (arg == null) {
                    for (final ParameterProvider<?> provider : methodParameterProviders.get(methodParameterInjectionPoint.getKey())) {
                        if (provider.getAnnotated().equals(paraInjectionPoint.getAnnotated())) {
                            arg = provider;
                            break;
                        }
                    }
                }

                args[i++] = arg;
            }

            final AnnotatedMethod<?> annotatedMethod = methodParameterInjectionPoint.getKey();

            resolver.resolveMethod(annotatedMethod, reference, args);
        }
    }

    /**
     * Resolves super class field dependencies for the specified reference.
     * 
     * @param reference the specified reference
     * @param clazz the super class of the specified reference
     * @throws Exception exception
     */
    private void resolveSuperclassFieldDependencies(final Object reference, final Class<?> clazz)
        throws Exception {
        if (clazz.equals(Object.class)) {
            return;
        }

        final Class<?> superclass = clazz.getSuperclass();

        resolveSuperclassFieldDependencies(reference, superclass);

        if (Modifier.isAbstract(clazz.getModifiers()) || Modifier.isInterface(clazz.getModifiers())) {
            return;
        }

        final BeanImpl<?> bean = (BeanImpl<?>) beanManager.getBean(clazz);
        final Set<FieldInjectionPoint> injectionPoints = bean.fieldInjectionPoints;

        for (final FieldInjectionPoint injectionPoint : injectionPoints) {
            Object injection = beanManager.getInjectableReference(injectionPoint, null);

            if (injection == null) {
                for (final FieldProvider<?> provider : bean.fieldProviders) {
                    if (provider.getAnnotated().equals(injectionPoint.getAnnotated())) {
                        injection = provider;
                        break;
                    }
                }
            }

            resolver.resolveField(injectionPoint.getAnnotated(), reference, injection);
        }
    }

    /**
     * Resolves super class method dependencies for the specified reference.
     * 
     * @param reference the specified reference
     * @param clazz the super class of the specified reference
     * @throws Exception exception
     */
    private void resolveSuperclassMethodDependencies(final Object reference, final Class<?> clazz) throws Exception {
        if (clazz.equals(Object.class)) {
            return;
        }

        final Class<?> superclass = clazz.getSuperclass();

        resolveSuperclassMethodDependencies(reference, superclass);

        if (Modifier.isAbstract(clazz.getModifiers()) || Modifier.isInterface(clazz.getModifiers())) {
            return;
        }

        final BeanImpl<?> bean = (BeanImpl<?>) beanManager.getBean(clazz);

        for (final Map.Entry<AnnotatedMethod<?>, List<ParameterInjectionPoint>> methodParameterInjectionPoint
            : bean.methodParameterInjectionPoints.entrySet()) {
            final List<ParameterInjectionPoint> paraSet = methodParameterInjectionPoint.getValue();
            final Object[] args = new Object[paraSet.size()];
            int i = 0;

            for (final ParameterInjectionPoint paraInjectionPoint : paraSet) {
                Object arg = beanManager.getInjectableReference(paraInjectionPoint, null);

                if (arg == null) {
                    for (final ParameterProvider<?> provider : bean.methodParameterProviders.get(methodParameterInjectionPoint.getKey())) {
                        if (provider.getAnnotated().equals(paraInjectionPoint.getAnnotated())) {
                            arg = provider;
                            break;
                        }
                    }
                }

                args[i++] = arg;
            }

            final AnnotatedMethod<?> annotatedMethod = methodParameterInjectionPoint.getKey();

            resolver.resolveMethod(annotatedMethod, reference, args);
        }
    }

    @Override
    public Set<Class<? extends Annotation>> getStereotypes() {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public boolean isAlternative() {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public boolean isNullable() {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public void destroy(final T instance, final CreationalContext<T> creationalContext) {
        LOGGER.log(Level.DEBUG, "Destroy bean [name={0}]", name);
    }

    @Override
    public BeanManager getBeanManager() {
        return beanManager;
    }

    @Override
    public Class<?> getBeanClass() {
        return beanClass;
    }

    @Override
    public Set<InjectionPoint> getInjectionPoints() {
        final Set<InjectionPoint> ret = new HashSet<InjectionPoint>();

        for (final List<ParameterInjectionPoint> constructorParameterInjectionPointList : constructorParameterInjectionPoints.values()) {
            ret.addAll(constructorParameterInjectionPointList);
        }

        ret.addAll(fieldInjectionPoints);

        for (final List<ParameterInjectionPoint> methodParameterInjectionPointList : methodParameterInjectionPoints.values()) {
            ret.addAll(methodParameterInjectionPointList);
        }

        return ret;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public Set<Annotation> getQualifiers() {
        return qualifiers;
    }

    @Override
    public Class<? extends Annotation> getScope() {
        return scope;
    }

    @Override
    public Set<Type> getTypes() {
        return types;
    }

    @Override
    public T create(final CreationalContext<T> creationalContext) {
        T ret = null;

        try {
            ret = instantiateReference();

            resolveDependencies(ret);
        } catch (final Exception ex) {
            LOGGER.log(Level.ERROR, ex.getMessage(), ex);
        }

        return ret;
    }

    @Override
    public LatkeBean<T> named(final String name) {
        final Named namedQualifier = new NamedLiteral(name);

        addQualifier(namedQualifier);
        return this;
    }

    @Override
    public LatkeBean<T> qualified(final Annotation qualifier,
        final Annotation... qualifiers) {
        addQualifier(qualifier);
        for (final Annotation q : qualifiers) {
            addQualifier(q);
        }

        return this;
    }

    @Override
    public LatkeBean<T> scoped(final Class<? extends Annotation> scope) {
        this.setScope(scope);
        return this;
    }

    @Override
    public String toString() {
        return "[name=" + name + ", scope=" + scope + ", qualifiers=" + qualifiers + ", class=" + beanClass.getName() + ", types=" + types
            + "]";
    }

    /**
     * Initializes constructor injection points.
     */
    private void initConstructorInjectionPoints() {
        final Set<AnnotatedConstructor<T>> annotatedConstructors = annotatedType.getConstructors();

        for (final AnnotatedConstructor annotatedConstructor : annotatedConstructors) {
            final List<AnnotatedParameter<?>> parameters = annotatedConstructor.getParameters();
            final List<ParameterInjectionPoint> paraInjectionPointArrayList = new ArrayList<ParameterInjectionPoint>();

            for (final AnnotatedParameter<?> annotatedParameter : parameters) {
                Type type = annotatedParameter.getBaseType();

                if (type instanceof ParameterizedType) {
                    type = ((ParameterizedType) type).getRawType();
                }

                if (type.equals(Provider.class)) {
                    final ParameterProvider<T> provider = new ParameterProvider<T>(beanManager, annotatedParameter);

                    constructorParameterProviders.add(provider);
                }

                final ParameterInjectionPoint parameterInjectionPoint = new ParameterInjectionPoint(this, annotatedParameter);

                paraInjectionPointArrayList.add(parameterInjectionPoint);
            }

            constructorParameterInjectionPoints.put(annotatedConstructor, paraInjectionPointArrayList);
        }
    }

    /**
     * Initializes method injection points.
     */
    @SuppressWarnings("unchecked")
    private void initMethodInjectionPoints() {
        final Set<AnnotatedMethod<? super T>> annotatedMethods = annotatedType.getMethods();

        for (final AnnotatedMethod annotatedMethod : annotatedMethods) {
            final List<AnnotatedParameter<?>> parameters = annotatedMethod.getParameters();
            final List<ParameterInjectionPoint> paraInjectionPointArrayList = new ArrayList<ParameterInjectionPoint>();
            final List<ParameterProvider<?>> paraProviders = new ArrayList<ParameterProvider<?>>();

            for (final AnnotatedParameter<?> annotatedParameter : parameters) {
                Type type = annotatedParameter.getBaseType();

                if (type instanceof ParameterizedType) {
                    type = ((ParameterizedType) type).getRawType();
                }

                if (type.equals(Provider.class)) {
                    final ParameterProvider<T> provider = new ParameterProvider<T>(beanManager, annotatedParameter);

                    paraProviders.add(provider);
                }

                final ParameterInjectionPoint parameterInjectionPoint = new ParameterInjectionPoint(this, annotatedParameter);

                paraInjectionPointArrayList.add(parameterInjectionPoint);
            }

            methodParameterProviders.put(annotatedMethod, paraProviders);
            methodParameterInjectionPoints.put(annotatedMethod, paraInjectionPointArrayList);
        }
    }

    /**
     * Initializes field injection points.
     */
    private void initFieldInjectionPoints() {
        final Set<AnnotatedField<? super T>> annotatedFields = annotatedType.getFields();

        for (final AnnotatedField<? super T> annotatedField : annotatedFields) {
            final Field field = annotatedField.getJavaMember();

            if (field.getType().equals(Provider.class)) { // by provider
                final FieldProvider<T> provider = new FieldProvider<T>(beanManager, annotatedField);

                fieldProviders.add(provider);

                final FieldInjectionPoint fieldInjectionPoint = new FieldInjectionPoint(this, annotatedField);

                fieldInjectionPoints.add(fieldInjectionPoint);
            } else { // by qualifier
                final FieldInjectionPoint fieldInjectionPoint = new FieldInjectionPoint(this, annotatedField);

                fieldInjectionPoints.add(fieldInjectionPoint);
            }
        }
    }

    /**
     * Sets the scope with the specified scope.
     * 
     * @param scope the specified scope
     */
    private void setScope(final Class<? extends Annotation> scope) {
        this.scope = scope;
    }

    /**
     * Adds a qualifier with the specified qualifier.
     * 
     * @param qualifier the specified qualifier
     */
    private void addQualifier(final Annotation qualifier) {
        if (qualifier.getClass().equals(NamedLiteral.class)) {
            final NamedLiteral namedQualifier = (NamedLiteral) getNamedQualifier();
            final NamedLiteral newNamedQualifier = (NamedLiteral) qualifier;

            if (!namedQualifier.value().equals(newNamedQualifier.value())) {
                setNamedQualifier(newNamedQualifier);
            }
        } else {
            qualifiers.add(qualifier);
        }

        configurator.addClassQualifierBinding(beanClass, qualifier);
    }

    /**
     * Sets the named qualifier with the specified named qualifier.
     * 
     * @param namedQualifier the specified named qualifier
     */
    private void setNamedQualifier(final Annotation namedQualifier) {
        for (final Annotation qualifier : qualifiers) {
            if (qualifier.annotationType().equals(Named.class)) {
                qualifiers.remove(qualifier);
                qualifiers.add(namedQualifier);
                name = ((Named) namedQualifier).value();
            }
        }
    }

    /**
     * Gets the named qualifier.
     * 
     * @return named aualifier
     */
    private Annotation getNamedQualifier() {
        for (final Annotation qualifier : qualifiers) {
            if (qualifier.annotationType().equals(Named.class)) {
                return qualifier;
            }
        }

        throw new RuntimeException("A bean has one qualifier(Named) at least!");
    }
}