package com.fasterxml.jackson.databind.ser;

import java.util.*;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.ObjectIdGenerator;
import com.fasterxml.jackson.annotation.ObjectIdGenerators;
import com.fasterxml.jackson.annotation.JsonTypeInfo.As;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.cfg.SerializerFactoryConfig;
import com.fasterxml.jackson.databind.introspect.*;
import com.fasterxml.jackson.databind.jsontype.TypeSerializer;
import com.fasterxml.jackson.databind.ser.impl.FilteredBeanPropertyWriter;
import com.fasterxml.jackson.databind.ser.impl.ObjectIdWriter;
import com.fasterxml.jackson.databind.ser.impl.PropertyBasedObjectIdGenerator;
import com.fasterxml.jackson.databind.ser.std.MapSerializer;
import com.fasterxml.jackson.databind.ser.std.StdDelegatingSerializer;
import com.fasterxml.jackson.databind.type.ReferenceType;
import com.fasterxml.jackson.databind.util.ClassUtil;
import com.fasterxml.jackson.databind.util.Converter;

/**
 * Factory class that can provide serializers for any regular Java beans
 * (as defined by "having at least one get method recognizable as bean
 * accessor" -- where {@link Object#getClass} does not count);
 * as well as for "standard" JDK types. Latter is achieved
 * by delegating calls to {@link BasicSerializerFactory} 
 * to find serializers both for "standard" JDK types (and in some cases,
 * sub-classes as is the case for collection classes like
 * {@link java.util.List}s and {@link java.util.Map}s) and bean (value)
 * classes.
 *<p>
 * Note about delegating calls to {@link BasicSerializerFactory}:
 * although it would be nicer to use linear delegation
 * for construction (to essentially dispatch all calls first to the
 * underlying {@link BasicSerializerFactory}; or alternatively after
 * failing to provide bean-based serializer}, there is a problem:
 * priority levels for detecting standard types are mixed. That is,
 * we want to check if a type is a bean after some of "standard" JDK
 * types, but before the rest.
 * As a result, "mixed" delegation used, and calls are NOT done using
 * regular {@link SerializerFactory} interface but rather via
 * direct calls to {@link BasicSerializerFactory}.
 *<p>
 * Finally, since all caching is handled by the serializer provider
 * (not factory) and there is no configurability, this
 * factory is stateless.
 * This means that a global singleton instance can be used.
 */
public class BeanSerializerFactory
    extends BasicSerializerFactory
    implements java.io.Serializable
{
    private static final long serialVersionUID = 3;

    /**
     * Like {@link BasicSerializerFactory}, this factory is stateless, and
     * thus a single shared global (== singleton) instance can be used
     * without thread-safety issues.
     */
    public final static BeanSerializerFactory instance = new BeanSerializerFactory(null);

    /*
    /**********************************************************************
    /* Life-cycle: creation, configuration
    /**********************************************************************
     */

    /**
     * Constructor for creating instances with specified configuration.
     */
    protected BeanSerializerFactory(SerializerFactoryConfig config)
    {
        super(config);
    }
    
    /**
     * Method used by module registration functionality, to attach additional
     * serializer providers into this serializer factory. This is typically
     * handled by constructing a new instance with additional serializers,
     * to ensure thread-safe access.
     */
    @Override
    public SerializerFactory withConfig(SerializerFactoryConfig config)
    {
        if (_factoryConfig == config) {
            return this;
        }
        /* 22-Nov-2010, tatu: Handling of subtypes is tricky if we do immutable-with-copy-ctor;
         *    and we pretty much have to here either choose between losing subtype instance
         *    when registering additional serializers, or losing serializers.
         *    Instead, let's actually just throw an error if this method is called when subtype
         *    has not properly overridden this method; this to indicate problem as soon as possible.
         */
        ClassUtil.verifyMustOverride(BeanSerializerFactory.class, this, "withConfig");
        return new BeanSerializerFactory(config);
    }

    /*
    /**********************************************************************
    /* SerializerFactory impl
    /**********************************************************************
     */

    /**
     * Main serializer constructor method. We will have to be careful
     * with respect to ordering of various method calls: essentially
     * we want to reliably figure out which classes are standard types,
     * and which are beans. The problem is that some bean Classes may
     * implement standard interfaces (say, {@link java.lang.Iterable}.
     *<p>
     * Note: sub-classes may choose to complete replace implementation,
     * if they want to alter priority of serializer lookups.
     */
    @Override
    @SuppressWarnings("unchecked")
    public JsonSerializer<Object> createSerializer(SerializerProvider ctxt, JavaType origType,
            BeanDescription beanDesc, JsonFormat.Value formatOverrides)
        throws JsonMappingException
    {
        // Very first thing, let's check if there is explicit serializer annotation:
        JsonSerializer<?> ser = findSerializerFromAnnotation(ctxt, beanDesc.getClassInfo());
        if (ser != null) {
            return (JsonSerializer<Object>) ser;
        }
        final SerializationConfig config = ctxt.getConfig();
        boolean staticTyping;
        // Next: we may have annotations that further indicate actual type to use (a super type)
        final AnnotationIntrospector intr = config.getAnnotationIntrospector();
        JavaType type;

        if (intr == null) {
            type = origType;
        } else {
            try {
                type = intr.refineSerializationType(config, beanDesc.getClassInfo(), origType);
            } catch (JsonMappingException e) {
                return ctxt.reportBadTypeDefinition(beanDesc, e.getMessage());
            }
        }
        if (type == origType) { // no changes, won't force static typing
            staticTyping = false;
        } else { // changes; assume static typing; plus, need to re-introspect if class differs
            staticTyping = true;
            if (!type.hasRawClass(origType.getRawClass())) {
                beanDesc = ctxt.introspectBeanDescription(type);
            }
        }
        // Slight detour: do we have a Converter to consider?
        Converter<Object,Object> conv = beanDesc.findSerializationConverter();
        if (conv != null) { // yup, need converter
            JavaType delegateType = conv.getOutputType(ctxt.getTypeFactory());
            
            // One more twist, as per [databind#288]; probably need to get new BeanDesc
            if (!delegateType.hasRawClass(type.getRawClass())) {
                beanDesc = ctxt.introspectBeanDescription(delegateType);
                // [#359]: explicitly check (again) for @JsonSerializer...
                ser = findSerializerFromAnnotation(ctxt, beanDesc.getClassInfo());
            }
            // [databind#731]: Should skip if nominally java.lang.Object
            if ((ser == null) && !delegateType.isJavaLangObject()) {
                ser = _createSerializer2(ctxt, beanDesc, delegateType, formatOverrides, true);
            }
            return new StdDelegatingSerializer(conv, delegateType, ser, null);
        }
        // No, regular serializer
        return (JsonSerializer<Object>) _createSerializer2(ctxt, beanDesc, type, formatOverrides, staticTyping);
    }

    protected JsonSerializer<?> _createSerializer2(SerializerProvider ctxt,
            BeanDescription beanDesc, JavaType type, JsonFormat.Value formatOverrides,
            boolean staticTyping)
        throws JsonMappingException
    {
        JsonSerializer<?> ser = null;
        final SerializationConfig config = ctxt.getConfig();
        
        // Container types differ from non-container types
        // (note: called method checks for module-provided serializers)
        if (type.isContainerType()) {
            if (!staticTyping) {
                staticTyping = usesStaticTyping(config, beanDesc, null);
            }
            // 03-Aug-2012, tatu: As per [databind#40], may require POJO serializer...
            ser =  buildContainerSerializer(ctxt, type, beanDesc, formatOverrides, staticTyping);
            // Will return right away, since called method does post-processing:
            if (ser != null) {
                return ser;
            }
        } else {
            if (type.isReferenceType()) {
                ser = findReferenceSerializer(ctxt, (ReferenceType) type, beanDesc, formatOverrides, staticTyping);
            } else {
                // Modules may provide serializers of POJO types:
                for (Serializers serializers : customSerializers()) {
                    ser = serializers.findSerializer(config, type, beanDesc, formatOverrides);
                    if (ser != null) {
                        break;
                    }
                }
            }
            // 25-Jun-2015, tatu: Then JsonSerializable, @JsonValue etc. NOTE! Prior to 2.6,
            //    this call was BEFORE custom serializer lookup, which was wrong.
            if (ser == null) {
                ser = findSerializerByAnnotations(ctxt, type, beanDesc);
            }
        }
        
        if (ser == null) {
            // Otherwise, we will check "primary types"; both marker types that
            // indicate specific handling (JsonSerializable), or main types that have
            // precedence over container types
            ser = findSerializerByLookup(type, config, beanDesc, formatOverrides, staticTyping);
            if (ser == null) {
                ser = findSerializerByPrimaryType(ctxt, type, beanDesc, formatOverrides, staticTyping);
                if (ser == null) {
                    // And this is where this class comes in: if type is not a
                    // known "primary JDK type", perhaps it's a bean? We can still
                    // get a null, if we can't find a single suitable bean property.
                    ser = constructBeanOrAddOnSerializer(ctxt, type, beanDesc, formatOverrides, staticTyping);
                    // Finally: maybe we can still deal with it as an implementation of some basic JDK interface?
                    if (ser == null) {
                        ser = ctxt.getUnknownTypeSerializer(beanDesc.getBeanClass());
                    }
                }
            }
        }
        // can not be null any more (always get at least "unknown" serializer)
        if (_factoryConfig.hasSerializerModifiers()) {
            for (BeanSerializerModifier mod : _factoryConfig.serializerModifiers()) {
                ser = mod.modifySerializer(config, beanDesc, ser);
            }
        }
        return ser;
    }

    /*
    /**********************************************************************
    /* Overridable non-public factory methods
    /**********************************************************************
     */

    /**
     * Method called to construct serializer based on checking which condition is matched:
     * <ol>
     *  <li>Nominal type is {@code java.lang.Object}: if so, return special "no type known" serializer
     *   </li>
     *  <li>If a known "not-POJO" type (like JDK {@code Proxy}), return {@code null}
     *   </li>
     *  <li>If at least one logical property found, build actual {@code BeanSerializer}
     *   </li>
     *  <li>If add-on type (like {@link java.lang.Iterable}) found, create appropriate serializer
     *   </li>
     *  <li>If one of Jackson's "well-known" annotations found, create bogus "empty Object" Serializer
     *   </li>
     *  </ol>
     *  or, if none matched, return {@code null}.
     */
    @SuppressWarnings("unchecked")
    protected JsonSerializer<Object> constructBeanOrAddOnSerializer(SerializerProvider ctxt,
            JavaType type, BeanDescription beanDesc, JsonFormat.Value format, boolean staticTyping)
        throws JsonMappingException
    {
        // 13-Oct-2010, tatu: quick sanity check: never try to create bean serializer for plain Object
        // 05-Jul-2012, tatu: ... but we should be able to just return "unknown type" serializer, right?
        if (beanDesc.getBeanClass() == Object.class) {
            return ctxt.getUnknownTypeSerializer(Object.class);
//            throw new IllegalArgumentException("Cannot create bean serializer for Object.class");
        }

        // We also know some types are not beans...
        if (!isPotentialBeanType(type.getRawClass())) {
            // Except we do need to allow serializers for Enums, if shape dictates (which it does
            // if we end up here)
            if (!type.isEnumType()) {
                return null;
            }
        }
        
        final SerializationConfig config = ctxt.getConfig();
        BeanSerializerBuilder builder = constructBeanSerializerBuilder(beanDesc);
        builder.setConfig(config);

        // First: any detectable (auto-detect, annotations) properties to serialize?
        List<BeanPropertyWriter> props = findBeanProperties(ctxt, beanDesc, builder);
        if (props == null) {
            props = new ArrayList<BeanPropertyWriter>();
        } else {
            props = removeOverlappingTypeIds(ctxt, beanDesc, builder, props);
        }
        
        // [databind#638]: Allow injection of "virtual" properties:
        ctxt.getAnnotationIntrospector().findAndAddVirtualProperties(config, beanDesc.getClassInfo(), props);

        // allow modification bean properties to serialize
        if (_factoryConfig.hasSerializerModifiers()) {
            for (BeanSerializerModifier mod : _factoryConfig.serializerModifiers()) {
                props = mod.changeProperties(config, beanDesc, props);
            }
        }

        // Any properties to suppress?
        props = filterBeanProperties(config, beanDesc, props);

        // Need to allow reordering of properties to serialize
        if (_factoryConfig.hasSerializerModifiers()) {
            for (BeanSerializerModifier mod : _factoryConfig.serializerModifiers()) {
                props = mod.orderProperties(config, beanDesc, props);
            }
        }

        // And if Object Id is needed, some preparation for that as well: better
        // do before view handling, mostly for the custom id case which needs
        // access to a property
        builder.setObjectIdWriter(constructObjectIdHandler(ctxt, beanDesc, props));
        
        builder.setProperties(props);
        builder.setFilterId(findFilterId(config, beanDesc));

        AnnotatedMember anyGetter = beanDesc.findAnyGetter();
        if (anyGetter != null) {
            JavaType anyType = anyGetter.getType();
            // copied from BasicSerializerFactory.buildMapSerializer():
            JavaType valueType = anyType.getContentType();
            TypeSerializer typeSer = ctxt.findTypeSerializer(valueType);
            // last 2 nulls; don't know key, value serializers (yet)
            // 23-Feb-2015, tatu: As per [databind#705], need to support custom serializers
            JsonSerializer<?> anySer = findSerializerFromAnnotation(ctxt, anyGetter);
            if (anySer == null) {
                // TODO: support '@JsonIgnoreProperties' with any setter?
                anySer = MapSerializer.construct(/* ignored props*/ (Set<String>) null,
                        anyType, config.isEnabled(MapperFeature.USE_STATIC_TYPING),
                        typeSer, null, null, /*filterId*/ null);
            }
            // TODO: can we find full PropertyName?
            PropertyName name = PropertyName.construct(anyGetter.getName());
            BeanProperty.Std anyProp = new BeanProperty.Std(name, valueType, null,
                    anyGetter, PropertyMetadata.STD_OPTIONAL);
            builder.setAnyGetter(new AnyGetterWriter(anyProp, anyGetter, anySer));
        }
        // Next: need to gather view information, if any:
        processViews(config, builder);

        // Finally: let interested parties mess with the result bit more...
        if (_factoryConfig.hasSerializerModifiers()) {
            for (BeanSerializerModifier mod : _factoryConfig.serializerModifiers()) {
                builder = mod.updateBuilder(config, beanDesc, builder);
            }
        }

        JsonSerializer<Object> ser = null;
        try {
            ser = (JsonSerializer<Object>) builder.build();
        } catch (RuntimeException e) {
            ctxt.reportBadTypeDefinition(beanDesc, "Failed to construct BeanSerializer for %s: (%s) %s",
                    beanDesc.getType(), e.getClass().getName(), e.getMessage());
        }
        if (ser == null) {
            // [databind#2390]: Need to consider add-ons before fallback "empty" serializer
            ser = (JsonSerializer<Object>) findSerializerByAddonType(ctxt, type, beanDesc, format, staticTyping);
            if (ser == null) {
                // If we get this far, there were no properties found, so no regular BeanSerializer
                // would be constructed. But, couple of exceptions.
                // First: if there are known annotations, just create 'empty bean' serializer
                if (beanDesc.hasKnownClassAnnotations()) {
                    return builder.createDummy();
                }
            }
        }
        return ser;
    }

    protected ObjectIdWriter constructObjectIdHandler(SerializerProvider ctxt,
            BeanDescription beanDesc, List<BeanPropertyWriter> props)
        throws JsonMappingException
    {
        ObjectIdInfo objectIdInfo = beanDesc.getObjectIdInfo();
        if (objectIdInfo == null) {
            return null;
        }
        ObjectIdGenerator<?> gen;
        Class<?> implClass = objectIdInfo.getGeneratorType();

        // Just one special case: Property-based generator is trickier
        if (implClass == ObjectIdGenerators.PropertyGenerator.class) { // most special one, needs extra work
            String propName = objectIdInfo.getPropertyName().getSimpleName();
            BeanPropertyWriter idProp = null;

            for (int i = 0, len = props.size() ;; ++i) {
                if (i == len) {
                    throw new IllegalArgumentException("Invalid Object Id definition for "+beanDesc.getBeanClass().getName()
                            +": cannot find property with name '"+propName+"'");
                }
                BeanPropertyWriter prop = props.get(i);
                if (propName.equals(prop.getName())) {
                    idProp = prop;
                    // Let's force it to be the first property to output
                    // (although it may still get rearranged etc)
                    if (i > 0) {
                        props.remove(i);
                        props.add(0, idProp);
                    }
                    break;
                }
            }
            JavaType idType = idProp.getType();
            gen = new PropertyBasedObjectIdGenerator(objectIdInfo, idProp);
            // one more thing: must ensure that ObjectIdWriter does not actually write the value:
            return ObjectIdWriter.construct(idType, (PropertyName) null, gen, objectIdInfo.getAlwaysAsId());
            
        } 
        // other types are simpler
        JavaType type = ctxt.constructType(implClass);
        // Could require type to be passed explicitly, but we should be able to find it too:
        JavaType idType = ctxt.getTypeFactory().findTypeParameters(type, ObjectIdGenerator.class)[0];
        gen = ctxt.objectIdGeneratorInstance(beanDesc.getClassInfo(), objectIdInfo);
        return ObjectIdWriter.construct(idType, objectIdInfo.getPropertyName(), gen,
                objectIdInfo.getAlwaysAsId());
    }

    /**
     * Method called to construct a filtered writer, for given view
     * definitions. Default implementation constructs filter that checks
     * active view type to views property is to be included in.
     */
    protected BeanPropertyWriter constructFilteredBeanWriter(BeanPropertyWriter writer,
            Class<?>[] inViews)
    {
        return FilteredBeanPropertyWriter.constructViewBased(writer, inViews);
    }
    
    protected PropertyBuilder constructPropertyBuilder(SerializationConfig config,
            BeanDescription beanDesc)
    {
        return new PropertyBuilder(config, beanDesc);
    }

    protected BeanSerializerBuilder constructBeanSerializerBuilder(BeanDescription beanDesc) {
        return new BeanSerializerBuilder(beanDesc);
    }
    
    /*
    /**********************************************************************
    /* Overridable non-public introspection methods
    /**********************************************************************
     */

    /**
     * Helper method used to skip processing for types that we know
     * cannot be (i.e. are never consider to be) beans: 
     * things like primitives, Arrays, Enums, and proxy types.
     *<p>
     * Note that usually we shouldn't really be getting these sort of
     * types anyway; but better safe than sorry.
     */
    protected boolean isPotentialBeanType(Class<?> type)
    {
        return (ClassUtil.canBeABeanType(type) == null) && !ClassUtil.isProxyType(type);
    }

    /**
     * Method used to collect all actual serializable properties.
     * Can be overridden to implement custom detection schemes.
     */
    protected List<BeanPropertyWriter> findBeanProperties(SerializerProvider ctxt,
            BeanDescription beanDesc, BeanSerializerBuilder builder)
        throws JsonMappingException
    {
        List<BeanPropertyDefinition> properties = beanDesc.findProperties();
        final SerializationConfig config = ctxt.getConfig();

        // ignore specified types
        removeIgnorableTypes(ctxt, beanDesc, properties);

        // and possibly remove ones without matching mutator...
        if (config.isEnabled(MapperFeature.REQUIRE_SETTERS_FOR_GETTERS)) {
            removeSetterlessGetters(config, beanDesc, properties);
        }

        // nothing? can't proceed (caller may or may not throw an exception)
        if (properties.isEmpty()) {
            return null;
        }
        // null is for value type serializer, which we don't have access to from here (ditto for bean prop)
        boolean staticTyping = usesStaticTyping(config, beanDesc, null);
        PropertyBuilder pb = constructPropertyBuilder(config, beanDesc);
        
        ArrayList<BeanPropertyWriter> result = new ArrayList<BeanPropertyWriter>(properties.size());
        for (BeanPropertyDefinition property : properties) {
            final AnnotatedMember accessor = property.getAccessor();
            // Type id? Requires special handling:
            if (property.isTypeId()) {
                if (accessor != null) {
                    builder.setTypeId(accessor);
                }
                continue;
            }
            // suppress writing of back references
            AnnotationIntrospector.ReferenceProperty refType = property.findReferenceType();
            if (refType != null && refType.isBackReference()) {
                continue;
            }
            if (accessor instanceof AnnotatedMethod) {
                result.add(_constructWriter(ctxt, property, pb, staticTyping, (AnnotatedMethod) accessor));
            } else {
                result.add(_constructWriter(ctxt, property, pb, staticTyping, (AnnotatedField) accessor));
            }
        }
        return result;
    }

    /*
    /**********************************************************************
    /* Overridable non-public methods for manipulating bean properties
    /**********************************************************************
     */

    /**
     * Overridable method that can filter out properties. Default implementation
     * checks annotations class may have.
     */
    protected List<BeanPropertyWriter> filterBeanProperties(SerializationConfig config,
            BeanDescription beanDesc, List<BeanPropertyWriter> props)
    {
        // 01-May-2016, tatu: Which base type to use here gets tricky, since
        //   it may often make most sense to use general type for overrides,
        //   but what we have here may be more specific impl type. But for now
        //   just use it as is.
        JsonIgnoreProperties.Value ignorals = config.getDefaultPropertyIgnorals(beanDesc.getBeanClass(),
                beanDesc.getClassInfo());
        if (ignorals != null) {
            Set<String> ignored = ignorals.findIgnoredForSerialization();
            if (!ignored.isEmpty()) {
                props.removeIf(beanPropertyWriter -> ignored.contains(beanPropertyWriter.getName()));
            }
        }
        return props;
    }

    /**
     * Method called to handle view information for constructed serializer,
     * based on bean property writers.
     *<p>
     * Note that this method is designed to be overridden by sub-classes
     * if they want to provide custom view handling. As such it is not
     * considered an internal implementation detail, and will be supported
     * as part of API going forward.
     */
    protected void processViews(SerializationConfig config, BeanSerializerBuilder builder)
    {
        // whether non-annotated fields are included by default or not is configurable
        List<BeanPropertyWriter> props = builder.getProperties();
        boolean includeByDefault = config.isEnabled(MapperFeature.DEFAULT_VIEW_INCLUSION);
        final int propCount = props.size();
        int viewsFound = 0;
        BeanPropertyWriter[] filtered = new BeanPropertyWriter[propCount];
        // Simple: view information is stored within individual writers, need to combine:
        for (int i = 0; i < propCount; ++i) {
            BeanPropertyWriter bpw = props.get(i);
            Class<?>[] views = bpw.getViews();
            if (views == null
                    // [databind#2311]: sometimes we add empty array
                    || views.length == 0) { // no view info? include or exclude by default?
                if (includeByDefault) {
                    filtered[i] = bpw;
                }
            } else {
                ++viewsFound;
                filtered[i] = constructFilteredBeanWriter(bpw, views);
            }
        }
        // minor optimization: if no view info, include-by-default, can leave out filtering info altogether:
        if (includeByDefault && viewsFound == 0) {
            return;
        }
        builder.setFilteredProperties(filtered);
    }

    /**
     * Method that will apply by-type limitations (as per [JACKSON-429]);
     * by default this is based on {@link com.fasterxml.jackson.annotation.JsonIgnoreType}
     * annotation but can be supplied by module-provided introspectors too.
     * Starting with 2.8 there are also "Config overrides" to consider.
     */
    protected void removeIgnorableTypes(SerializerProvider ctxt, BeanDescription beanDesc,
            List<BeanPropertyDefinition> properties)
    {
        AnnotationIntrospector intr = ctxt.getAnnotationIntrospector();
        HashMap<Class<?>,Boolean> ignores = new HashMap<Class<?>,Boolean>();
        Iterator<BeanPropertyDefinition> it = properties.iterator();
        while (it.hasNext()) {
            BeanPropertyDefinition property = it.next();
            AnnotatedMember accessor = property.getAccessor();
            // 22-Oct-2016, tatu: Looks like this removal is an important part of
            //    processing, as taking it out will result in a few test failures...
            //    But should probably be done somewhere else, not here?
            if (accessor == null) {
                it.remove();
                continue;
            }
            Class<?> type = property.getRawPrimaryType();
            Boolean result = ignores.get(type);
            if (result == null) {
                result = ctxt.getConfig().getConfigOverride(type).getIsIgnoredType();
                if (result == null) {
                    AnnotatedClass ac = ctxt.introspectClassAnnotations(type);
                    result = intr.isIgnorableType(ac);
                    // default to false, non-ignorable
                    if (result == null) {
                        result = Boolean.FALSE;
                    }
                }
                ignores.put(type, result);
            }
            // lotsa work, and yes, it is ignorable type, so:
            if (result.booleanValue()) {
                it.remove();
            }
        }
    }

    /**
     * Helper method that will remove all properties that do not have a mutator.
     */
    protected void removeSetterlessGetters(SerializationConfig config, BeanDescription beanDesc,
            List<BeanPropertyDefinition> properties)
    {
        // one caveat: only remove implicit properties;
        // explicitly annotated ones should remain
        properties.removeIf(property -> !property.couldDeserialize() && !property.isExplicitlyIncluded());
    }

    /**
     * Helper method called to ensure that we do not have "duplicate" type ids.
     * Added to resolve [databind#222]
     */
    protected List<BeanPropertyWriter> removeOverlappingTypeIds(SerializerProvider ctxt,
            BeanDescription beanDesc, BeanSerializerBuilder builder,
            List<BeanPropertyWriter> props)
    {
        for (int i = 0, end = props.size(); i < end; ++i) {
            BeanPropertyWriter bpw = props.get(i);
            TypeSerializer td = bpw.getTypeSerializer();
            if ((td == null) || (td.getTypeInclusion() != As.EXTERNAL_PROPERTY)) {
                continue;
            }
            String n = td.getPropertyName();
            PropertyName typePropName = PropertyName.construct(n);

            for (BeanPropertyWriter w2 : props) {
                if ((w2 != bpw) && w2.wouldConflictWithName(typePropName)) {
                    bpw.assignTypeSerializer(null);
                    break;
                }
            }
        }
        return props;
    }

    /*
    /**********************************************************************
    /* Internal helper methods
    /**********************************************************************
     */

    /**
     * Secondary helper method for constructing {@link BeanPropertyWriter} for
     * given member (field or method).
     */
    protected BeanPropertyWriter _constructWriter(SerializerProvider ctxt,
            BeanPropertyDefinition propDef,
            PropertyBuilder pb, boolean staticTyping, AnnotatedMember accessor)
        throws JsonMappingException
    {
        final PropertyName name = propDef.getFullName();
        JavaType type = accessor.getType();
        BeanProperty.Std property = new BeanProperty.Std(name, type, propDef.getWrapperName(),
                accessor, propDef.getMetadata());

        // Does member specify a serializer? If so, let's use it.
        JsonSerializer<?> annotatedSerializer = findSerializerFromAnnotation(ctxt,
                accessor);
        // Unlike most other code paths, serializer produced
        // here will NOT be resolved or contextualized, unless done here, so:
        if (annotatedSerializer != null) {
            annotatedSerializer.resolve(ctxt);
            // 05-Sep-2013, tatu: should be primary property serializer so:
            annotatedSerializer = ctxt.handlePrimaryContextualization(annotatedSerializer, property);
        }
        // And how about polymorphic typing? First special to cover JAXB per-field settings:
        TypeSerializer contentTypeSer = null;
        // 16-Feb-2014, cgc: contentType serializers for collection-like and map-like types
        if (type.isContainerType() || type.isReferenceType()) {
            contentTypeSer = findPropertyContentTypeSerializer(ctxt, type, accessor);
        }
        // and if not JAXB collection/array with annotations, maybe regular type info?
        TypeSerializer typeSer = ctxt.findPropertyTypeSerializer(type, accessor);
        return pb.buildWriter(ctxt, propDef, type, annotatedSerializer,
                        typeSer, contentTypeSer, accessor, staticTyping);
    }
}
