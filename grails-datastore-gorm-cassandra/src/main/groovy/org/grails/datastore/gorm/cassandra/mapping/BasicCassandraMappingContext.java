package org.grails.datastore.gorm.cassandra.mapping;

import java.beans.PropertyDescriptor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.util.*;

import grails.core.GrailsDomainClassProperty;
import org.grails.datastore.mapping.cassandra.config.CassandraMappingContext;
import org.grails.datastore.mapping.cassandra.config.Table;
import org.grails.datastore.mapping.model.DatastoreConfigurationException;
import org.grails.datastore.mapping.model.IllegalMappingException;
import org.grails.datastore.mapping.model.PersistentEntity;
import org.springframework.cassandra.core.keyspace.CreateTableSpecification;
import org.springframework.cassandra.core.keyspace.DefaultOption;
import org.springframework.cassandra.core.keyspace.Option;
import org.springframework.cassandra.core.keyspace.TableOption;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.data.cassandra.mapping.BasicCassandraPersistentProperty;
import org.springframework.data.cassandra.mapping.CassandraPersistentEntity;
import org.springframework.data.cassandra.mapping.CassandraPersistentProperty;
import org.springframework.data.cassandra.mapping.CassandraSimpleTypeHolder;
import org.springframework.data.mapping.model.SimpleTypeHolder;
import org.springframework.data.util.TypeInformation;
import org.springframework.validation.Errors;

/**
 * Extends default
 * {@link org.springframework.data.cassandra.mapping.BasicCassandraMappingContext}
 * to create CassandraPersistentProperty for GORM types not supported by Spring
 * Data Cassandra or Cassandra
 *
 */
public class BasicCassandraMappingContext extends org.springframework.data.cassandra.mapping.BasicCassandraMappingContext {

	private static final Map<String, Class<? extends Enum>> KEY_TO_OPTION = new LinkedHashMap<String, Class<? extends Enum>>() {{
		put("CACHING", TableOption.CachingOption.class);
		put("COMPACTION", TableOption.CompactionOption.class);
		put("COMPRESSION", TableOption.CompressionOption.class);
	}};

	public static final String INTERNAL_MARKER = "$";
	public static final String INTERNAL_GRAILS_FIELD_MARKER = "org_grails";
	CassandraMappingContext gormCassandraMappingContext;
	
	public BasicCassandraMappingContext(CassandraMappingContext gormCassandraMappingContext) {
		this.gormCassandraMappingContext = gormCassandraMappingContext;
	}

	@Override
	protected CassandraPersistentEntity<?> addPersistentEntity(TypeInformation<?> typeInformation) {
		if(!typeInformation.getType().isInterface()) {
			return super.addPersistentEntity(typeInformation);
		}
		return null;
	}

	@Override
	public CreateTableSpecification getCreateTableSpecificationFor(CassandraPersistentEntity<?> entity) {
		CreateTableSpecification tableSpecification = super.getCreateTableSpecificationFor(entity);
		PersistentEntity gormEntity = gormCassandraMappingContext.getPersistentEntity(entity.getName());
		Table table = (Table) gormEntity.getMapping().getMappedForm();

		Map<String, Object> tableProperties = table.getTableProperties();
		if(tableProperties != null && !tableProperties.isEmpty()) {
			for (Map.Entry<String, Object> option : tableProperties.entrySet()) {
				String k = option.getKey();
				String optionName = resolveOptionName(k);
				Object value = option.getValue();
				try {
					TableOption to = TableOption.valueOf(optionName);
					if(value instanceof Boolean) {
						if((Boolean) value) {
							tableSpecification.with(to);
						}
					}
					else if(value instanceof Map) {
						Map mapValue = (Map) value;
						if(KEY_TO_OPTION.containsKey(optionName)) {
							Class<? extends Enum> enumClass = KEY_TO_OPTION.get(optionName);
							for (Object key : new HashSet<>(mapValue.keySet())) {
								String subOption = resolveOptionName(key.toString());
								try {
									Enum subopt = Enum.valueOf(enumClass, subOption);
									Object v = mapValue.get(key);
									mapValue.remove(key);
									mapValue.put(subopt, v);
								} catch (Exception e) {
									throw new IllegalMappingException("Invalid ["+optionName+"] option ["+ key +"] for parent option ["+key+"] for entity ["+ entity.getName() +"]: " + e.getMessage());
								}
							}
						}
						tableSpecification.with(to, mapValue);
					}
					else {
						tableSpecification.with(to, value);
					}
				} catch (Throwable e) {
					throw new IllegalMappingException("Invalid table option ["+ k +"] for entity ["+ entity.getName() +"]: " + e.getMessage());
				}
			}
		}

		return tableSpecification;
	}

	private String resolveOptionName(String k) {
		return k.toUpperCase().replace(' ', '_');
	}

	@Override
	public CassandraPersistentProperty createPersistentProperty(Field field, PropertyDescriptor descriptor, CassandraPersistentEntity<?> owner, SimpleTypeHolder simpleTypeHolder) {
		PersistentEntity gormEntity = gormCassandraMappingContext.getPersistentEntity(owner.getName());
		final CassandraPersistentProperty property = super.createPersistentProperty(field, descriptor, owner, simpleTypeHolder);
		final CassandraPersistentProperty transientProperty = new BasicCassandraPersistentProperty(field, descriptor, owner, (CassandraSimpleTypeHolder) simpleTypeHolder) {
			public boolean isTransient() {
				return true;
			}
		};
		if (field == null && !property.usePropertyAccess()) {
			return transientProperty;
		}
		if (field != null && Modifier.isTransient(field.getModifiers())) {
			return transientProperty;
		}
		if (field != null && grails.core.GrailsDomainClassProperty.ERRORS.equals(field.getName())) {
			return transientProperty;
		}

		if (field != null && field.getType().equals(Errors.class)) {
			return transientProperty;
		}

		if(field != null && (field.getName().contains(INTERNAL_MARKER) || field.getName().startsWith(INTERNAL_GRAILS_FIELD_MARKER))) {
			return transientProperty;
		}

		if(descriptor != null && descriptor.getWriteMethod() == null && descriptor.getReadMethod() == null) {
			return transientProperty;
		}

		Class<?> rawType = field != null ? field.getType() : descriptor != null ? descriptor.getPropertyType() : null;
		if (rawType == null) {
			return transientProperty;
		}
		if (rawType.isEnum()) {
			// persist as a string
			return new BasicCassandraPersistentProperty(field, descriptor, owner, (CassandraSimpleTypeHolder) simpleTypeHolder) {
				public com.datastax.driver.core.DataType getDataType() {
					return CassandraSimpleTypeHolder.getDataTypeFor(String.class);
				};

				public boolean usePropertyAccess() {
					return true;
				};
			};
		} else if (URL.class.isAssignableFrom(rawType) || TimeZone.class.isAssignableFrom(rawType) || Locale.class.isAssignableFrom(rawType) || Currency.class.isAssignableFrom(rawType) || Calendar.class.isAssignableFrom(rawType)) {
			// persist as a string
			return new BasicCassandraPersistentProperty(field, descriptor, owner, (CassandraSimpleTypeHolder) simpleTypeHolder) {
				public com.datastax.driver.core.DataType getDataType() {
					return CassandraSimpleTypeHolder.getDataTypeFor(String.class);
				};

				public boolean isEntity() {
					return false;
				};

				public boolean usePropertyAccess() {
					return true;
				};
			};
		} else if (field != null && gormEntity != null && GrailsDomainClassProperty.VERSION.equals(field.getName()) && !gormEntity.isVersioned()) {
			return transientProperty;
		}
		

		// for collections or maps of non-primitive types, i.e associations,
		// return transient property as spring data cassandra doesn't support
		if (!property.isTransient()) {
			if (property.isMap() || property.isCollectionLike()) {
				try {
					property.getDataType();
				} catch (InvalidDataAccessApiUsageException e) {
					return transientProperty;
				}
			}
		}
		return property;
	}
}
