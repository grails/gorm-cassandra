package org.grails.datastore.gorm.cassandra.mapping;

import com.datastax.driver.core.querybuilder.Insert;
import org.grails.datastore.mapping.model.types.BasicTypeConverterRegistrar;
import org.grails.datastore.mapping.model.types.conversion.StringToCurrencyConverter;
import org.grails.datastore.mapping.model.types.conversion.StringToLocaleConverter;
import org.grails.datastore.mapping.model.types.conversion.StringToTimeZoneConverter;
import org.grails.datastore.mapping.model.types.conversion.StringToURLConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.convert.support.DefaultConversionService;
import org.springframework.data.cassandra.convert.CassandraPersistentEntityParameterValueProvider;
import org.springframework.data.cassandra.convert.ColumnReader;
import org.springframework.data.cassandra.mapping.CassandraMappingContext;
import org.springframework.data.cassandra.mapping.CassandraPersistentEntity;
import org.springframework.data.cassandra.mapping.CassandraPersistentProperty;
import org.springframework.data.convert.EntityInstantiator;
import org.springframework.data.mapping.PersistentProperty;
import org.springframework.data.mapping.PersistentPropertyAccessor;
import org.springframework.data.mapping.PropertyHandler;
import org.springframework.data.mapping.model.ConvertingPropertyAccessor;
import org.springframework.data.mapping.model.DefaultSpELExpressionEvaluator;
import org.springframework.data.mapping.model.SpELExpressionEvaluator;

import com.datastax.driver.core.DataType;
import com.datastax.driver.core.Row;

/**
 * Overridden classes to: 
 * - workaround BIGINT/varint bug in Spring Data Cassandra. 
 * TODO: Remove readEntityFromRow/BasicCassandraRowValueProvider once fixed in Spring Data Cassandra project. 
 * - add extra converters for Common GORM properties, cannot override super class conversionService as it is declared final for some reason
 * 
 */
public class MappingCassandraConverter extends org.springframework.data.cassandra.convert.MappingCassandraConverter {
    private final Logger log = LoggerFactory.getLogger(getClass());
    public MappingCassandraConverter(CassandraMappingContext cassandraMapping) {
        super(cassandraMapping);
        DefaultConversionService conversionService = (DefaultConversionService) getConversionService();
        conversionService.addConverter(new StringToCurrencyConverter());
        conversionService.addConverter(new StringToLocaleConverter());
        conversionService.addConverter(new StringToTimeZoneConverter());
        conversionService.addConverter(new TimeZoneToStringConverter());
        conversionService.addConverter(new StringToURLConverter());
        BasicTypeConverterRegistrar registrar = new BasicTypeConverterRegistrar();
        registrar.register(conversionService);
    }

    /**
     * TODO: remove once BIGINT/varint bug fixed in Spring Data Cassandra. 
     */
    @Override
    protected <S> S readEntityFromRow(CassandraPersistentEntity<S> entity, Row row) {
        DefaultSpELExpressionEvaluator evaluator = new DefaultSpELExpressionEvaluator(row, spELContext);

        BasicCassandraRowValueProvider rowValueProvider = new BasicCassandraRowValueProvider(row, evaluator);

        CassandraPersistentEntityParameterValueProvider parameterProvider = new CassandraPersistentEntityParameterValueProvider(entity, rowValueProvider, null);

        EntityInstantiator instantiator = instantiators.getInstantiatorFor(entity);
        S instance = instantiator.createInstance(entity, parameterProvider);


        PersistentPropertyAccessor accessor = instance instanceof PersistentPropertyAccessor
                ? (PersistentPropertyAccessor) instance : entity.getPropertyAccessor(instance);


        readPropertiesFromRow(entity, rowValueProvider, new ConvertingPropertyAccessor(accessor, conversionService) {
            @Override
            public void setProperty(PersistentProperty<?> property, Object value) {
                Class actualType = property.getTypeInformation().getType();
                if(actualType.isEnum() && (value instanceof CharSequence)) {
                    super.setProperty(property, conversionService.convert(value, actualType));
                }
                else {
                    super.setProperty(property, value);
                }
            }
        } );

        return (S) accessor.getBean();
    }

    @Override
    protected void writeInsertFromWrapper(final ConvertingPropertyAccessor accessor, final Insert insert,
                                          final CassandraPersistentEntity<?> entity) {

        entity.doWithProperties(new PropertyHandler<CassandraPersistentProperty>() {

            @Override
            public void doWithPersistentProperty(CassandraPersistentProperty prop) {

                Object value = accessor.getProperty(prop, prop.getType());

                if(log.isDebugEnabled()) {
                    log.debug("prop.type -> " + prop.getType().getName());
                    log.debug("prop.value -> " + value);
                }

                if(prop.getDataType().equals(DataType.text()) && !CharSequence.class.isAssignableFrom(prop.getType())) {
                    value = conversionService.convert(value, String.class);
                }

                if (prop.isCompositePrimaryKey()) {
                    if(log.isDebugEnabled()) {
                        log.debug("prop is a compositeKey");
                    }
                    PersistentPropertyAccessor accessor = value instanceof PersistentPropertyAccessor
                            ? (PersistentPropertyAccessor) value : prop.getCompositePrimaryKeyEntity().getPropertyAccessor(value);

                    accessor = new ConvertingPropertyAccessor(accessor, conversionService);
                    writeInsertFromWrapper((ConvertingPropertyAccessor) accessor, insert,
                            prop.getCompositePrimaryKeyEntity());
                    return;
                }

                if (value != null) {
                    if(log.isDebugEnabled()) {
                        log.debug(String.format("Adding insert.value [%s] - [%s]", prop.getColumnName().toCql(), value));
                    }
                    insert.value(prop.getColumnName().toCql(), value);
                }
            }
        });
    }

    /**
     *  TODO: remove once BIGINT/varint bug fixed in Spring Data Cassandra.          
     */
    private static class BasicCassandraRowValueProvider extends org.springframework.data.cassandra.convert.BasicCassandraRowValueProvider {

        private final ColumnReader reader;
        private final SpELExpressionEvaluator evaluator;

        /**
         * Creates a new {@link BasicCassandraRowValueProvider} with the given
         * {@link Row} and {@link DefaultSpELExpressionEvaluator}.
         * 
         * @param source
         *            must not be {@literal null}.
         * @param evaluator
         *            must not be {@literal null}.
         */
        public BasicCassandraRowValueProvider(Row source, DefaultSpELExpressionEvaluator evaluator) {

            super(source, evaluator);

            this.reader = new ColumnReader(source) {
                @Override
                public Object get(int i) {
                    DataType type = columns.getType(i);
                    if (type.equals(DataType.varint())) {
                        return row.getVarint(i);
                    }
                    return super.get(i);
                }
            };
            this.evaluator = evaluator;
        }

        @Override
        @SuppressWarnings("unchecked")
        public Object getPropertyValue(CassandraPersistentProperty property) {

            String expression = property.getSpelExpression();
            if (expression != null) {
                return evaluator.evaluate(expression);
            }

            return reader.get(property.getColumnName());
        }

        @Override
        public Row getRow() {
            return reader.getRow();
        }
    }
}
