package org.grails.datastore.mapping.cassandra.config

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import org.springframework.cassandra.core.keyspace.CreateIndexSpecification
import org.springframework.cassandra.core.keyspace.DropIndexSpecification
import org.springframework.data.cassandra.config.CassandraSessionFactoryBean
import org.springframework.data.cassandra.config.SchemaAction
import org.springframework.data.cassandra.core.CassandraAdminOperations
import org.springframework.data.cassandra.mapping.CassandraPersistentEntity
import org.springframework.data.cassandra.mapping.CassandraPersistentProperty
import org.springframework.data.mapping.PropertyHandler

/**
 * Extends {@link CassandraSessionFactoryBean} in order to build the index
 *
 * @author Graeme Rocher
 * @since 6.0.6
 */
@CompileStatic
@Slf4j
class GormCassandraSessionFactoryBean extends CassandraSessionFactoryBean {

    @Override
    protected void performSchemaAction() {
        super.performSchemaAction()
        buildIndex()
    }

    /**
     * Builds the cassandra index based on the schema operation
     */
    void buildIndex() {
        Collection<CassandraPersistentEntity> entities = mappingContext.getNonPrimaryKeyEntities()

        for (CassandraPersistentEntity entity : entities) {
            buildIndex(entity)
        }
    }

    void createTableIfNecessary(CassandraPersistentEntity entity) {
        boolean isRecreate = SchemaAction.RECREATE == schemaAction || SchemaAction.RECREATE_DROP_UNUSED == schemaAction
        boolean createIfNotExists = SchemaAction.CREATE_IF_NOT_EXISTS == schemaAction
        boolean isCreate = isRecreate || SchemaAction.CREATE == schemaAction || createIfNotExists
        if(isCreate) {
            getCassandraAdminOperations().createTable(createIfNotExists, entity.getTableName(), entity.getType(), null);
            buildIndex(entity)
        }
    }

    protected void buildIndex(CassandraPersistentEntity entity) {
        boolean isRecreate = SchemaAction.RECREATE == schemaAction || SchemaAction.RECREATE_DROP_UNUSED == schemaAction
        boolean createIfNotExists = SchemaAction.CREATE_IF_NOT_EXISTS == schemaAction
        boolean isCreate = isRecreate || SchemaAction.CREATE == schemaAction || createIfNotExists
        CassandraAdminOperations adminOperations = getCassandraAdminOperations()
        entity.doWithProperties(new PropertyHandler<CassandraPersistentProperty>() {
            @Override
            void doWithPersistentProperty(CassandraPersistentProperty cpp) {
                if (cpp.isIndexed()) {
                    if (isCreate) {
                        CreateIndexSpecification cis = new CreateIndexSpecification()
                        cis = cis.ifNotExists(createIfNotExists)
                                .columnName(cpp.columnName)
                                .tableName(entity.tableName)

                        adminOperations.execute(cis)
                    }
                }
            }
        })
    }
}
