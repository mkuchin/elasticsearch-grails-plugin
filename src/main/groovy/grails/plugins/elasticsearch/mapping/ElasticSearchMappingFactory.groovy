/*
 * Copyright 2002-2010 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License")
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package grails.plugins.elasticsearch.mapping

import grails.core.GrailsDomainClass
import grails.core.GrailsDomainClassProperty
import grails.plugins.GrailsPluginManager
import grails.util.GrailsNameUtils
import grails.util.Holders
import groovy.transform.CompileStatic
import org.springframework.util.ClassUtils

/**
 * Build ElasticSearch class mapping based on attributes provided by closure.
 */
@CompileStatic
class ElasticSearchMappingFactory {

    private static final Set<String> SUPPORTED_FORMAT =
            ['string', 'integer', 'long', 'float', 'double', 'boolean', 'null', 'date'] as Set<String>

    private static Class JODA_TIME_BASE

    static Map<String, String> javaPrimitivesToElastic =
            [int: 'integer', long: 'long', short: 'short', double: 'double', float: 'float', byte: 'byte']

    static {
        try {
            JODA_TIME_BASE = Class.forName('org.joda.time.ReadableInstant')
        } catch (ClassNotFoundException e) {
        }
    }

    static Map<String, Object> getElasticMapping(SearchableClassMapping scm) {
        Map mappingFields = [properties: getMappingProperties(scm)]

        if (scm.@all instanceof Map) {
            mappingFields.'_all' = scm.@all as Map
        }
        if (!scm.isAll())
            mappingFields.'_all' = Collections.singletonMap('enabled', false)

        SearchableClassPropertyMapping parentProperty = scm.propertiesMapping.find { it.parent }
        if (parentProperty) {
            mappingFields.'_parent' = [type: GrailsNameUtils.getPropertyName(parentProperty.grailsProperty.type)]
        }

        Map<String, Object> mapping = [:]
        mapping.put("${scm.getElasticTypeName()}" as String,  mappingFields)
        mapping
    }

    private static Map<String, Object> getMappingProperties(SearchableClassMapping scm) {
        Map<String, Object> elasticTypeMappingProperties = [:]

        // Map each domain properties in supported format, or object for complex type
        scm.getPropertiesMapping().each { SearchableClassPropertyMapping scpm ->
            // Does it have custom mapping?
            Map<String, Object> propOptions = [:]
            // Add the custom mapping (searchable static property in domain model)
            propOptions.putAll(scpm.getAttributes())
            String propType = getElasticType(scpm)
            if (!scpm.isGeoPoint()) {
                if (scpm.isComponent()) {
                    // Proceed with nested mapping.
                    // todo limit depth to avoid endless recursion?
                    //noinspection unchecked
                    propOptions.putAll((Map<String, Object>)
                            (getElasticMapping(scpm.getComponentPropertyMapping()).values().iterator().next()))
                }

                // Once it is an object, we need to add id & class mappings, otherwise
                // ES will fail with NullPointer.
                if (scpm.isComponent() || scpm.getReference() != null) {
                    Map<String, Object> props = (Map<String, Object>) propOptions.'properties'
                    if (props == null) {
                        props = [:]
                        propOptions.properties = props
                    }
                    GrailsDomainClass referencedDomainClass = scpm.grailsProperty.getReferencedDomainClass()
                    GrailsDomainClassProperty idProperty = referencedDomainClass.getPropertyByName('id')
                    String idType = idProperty.getTypePropertyName()

                    if (idTypeIsMongoObjectId(idType)) {
                        idType = treatValueAsAString(idType)
                    } else if (idTypeIsUUID(idType)) {
                        idType = 'string'
                    }

                    props.put('id', defaultDescriptor(idType, 'not_analyzed', true))
                    props.put('class', defaultDescriptor('string', 'no', true))
                    props.put('ref', defaultDescriptor('string', 'no', true))
                }
            }
            propOptions.type = propType
            // See http://www.elasticsearch.com/docs/elasticsearch/mapping/all_field/
            if (!(propType in ['object', 'attachment']) && scm.isAll()) {
                // does it make sense to include objects into _all?
                propOptions.include_in_all = !scpm.shouldExcludeFromAll()
            }
            // todo only enable this through configuration...
            if(propType == 'string' && scpm.isDynamic()) {
                propOptions.type = 'object'
                propOptions.dynamic = true
            } else if ((propType == 'string') && scpm.isAnalyzed()) {
                propOptions.term_vector = 'with_positions_offsets'
            }
            if (scpm.isMultiField()) {
                Map<String, Object> field = new LinkedHashMap<String, Object>(propOptions)
                Map untouched = [:]
                untouched.put('type', propOptions.get('type'))
                untouched.put('index', 'not_analyzed')

                Map fields = [untouched: untouched]
                fields.put("${scpm.getPropertyName()}" as String, field)

                propOptions = [:]
                propOptions.type = 'multi_field'
                propOptions.fields = fields
            }
            if (propType == 'object' && scpm.component && !scpm.innerComponent) {
                propOptions.type = 'nested'
            }
            elasticTypeMappingProperties.put(scpm.getPropertyName(), propOptions)
        }
        elasticTypeMappingProperties
    }

    private static String getElasticType(SearchableClassPropertyMapping scpm) {
        String propType = null

        if (scpm.isGeoPoint()) {
            propType = 'geo_point'
        } else if(scpm.isAttachment()) {
            propType = 'attachment'
        } else {
            propType = scpm.grailsProperty.getTypePropertyName()

            //Preprocess collections and arrays to work with it's element types
            Class referencedPropertyType = scpm.grailsProperty.getReferencedPropertyType()
            if(Collection.isAssignableFrom(referencedPropertyType) || referencedPropertyType.isArray()) {
                //Handle collections explictly mapped (needed for dealing with transients)
                if (scpm.grailsProperty.domainClass.associationMap[scpm.grailsProperty.name]) {
                    referencedPropertyType = scpm.grailsProperty.domainClass.associationMap[scpm.grailsProperty.name]
                }
                if (referencedPropertyType.isArray()) {
                    referencedPropertyType = referencedPropertyType.getComponentType()
                }
                String basicType = getTypeSimpleName(referencedPropertyType)
                if (SUPPORTED_FORMAT.contains(basicType)) {
                    propType = basicType
                }
            } else if (!SUPPORTED_FORMAT.contains(propType) && SUPPORTED_FORMAT.contains(getTypeSimpleName(referencedPropertyType))) {
                propType = getTypeSimpleName(referencedPropertyType)
            }

            //Handle unsupported types
            if (!(SUPPORTED_FORMAT.contains(propType))) {
                if (isDateType(referencedPropertyType)) {
                    propType = 'date'
                } else if (referencedPropertyType.isEnum()) {
                    propType = 'string'
                } else if (scpm.getConverter() != null) {
                    // Use 'string' type for properties with custom converter.
                    // Arrays are automatically resolved by ElasticSearch, so no worries.
                    def requestedConverter = scpm.getConverter()
                    propType = (SUPPORTED_FORMAT.contains(requestedConverter)) ? requestedConverter : 'string'
                    // Handle primitive types, see https://github.com/mstein/elasticsearch-grails-plugin/issues/61
                } else if (referencedPropertyType.isPrimitive()) {
                    if (javaPrimitivesToElastic.containsKey(referencedPropertyType.toString())) {
                        propType = javaPrimitivesToElastic.get(referencedPropertyType.toString())
                    } else {
                        propType = 'object'
                    }
                } else if (isBigDecimalType(referencedPropertyType)) {
                    propType = 'double'
                } else {
                    propType = 'object'
                }

                if (scpm.getReference() != null) {
                    propType = 'object'      // fixme: think about composite ids.
                } else if (scpm.isComponent()) {
                    // Proceed with nested mapping.
                    // todo limit depth to avoid endless recursion?
                    propType = 'object'
                }
            }
        }

        propType
    }

    private static String getTypeSimpleName(Class type){
        ClassUtils.getShortName(type).toLowerCase(Locale.ENGLISH)
    }

    private static boolean idTypeIsMongoObjectId(String idType) {
        idType.equals('objectId')
    }

    private static boolean idTypeIsUUID(String idType) {
        idType.equalsIgnoreCase('uuid')
    }

    private static String treatValueAsAString(String idType) {
        if ((Holders.grailsApplication.config.elasticSearch as ConfigObject).datastoreImpl =~ /mongo/) {
            idType = 'string'
        } else {
            def pluginManager = Holders.applicationContext.getBean(GrailsPluginManager.BEAN_NAME)
            if (((GrailsPluginManager) pluginManager).hasGrailsPlugin('mongodb')) {
                idType = 'string'
            }
        }
        idType
    }

    private static boolean isDateType(Class type) {
        (JODA_TIME_BASE != null && JODA_TIME_BASE.isAssignableFrom(type)) || Date.isAssignableFrom(type)
    }

    private static boolean isBigDecimalType(Class type) {
        BigDecimal.isAssignableFrom(type)
    }

    private static Map<String, Object> defaultDescriptor(String type, String index, boolean excludeFromAll) {
        [type: type, index: index, include_in_all: !excludeFromAll]
    }
}
