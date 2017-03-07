package grails.gorm.tests

import spock.lang.Ignore

/**
 * Created by graemerocher on 07/03/2017.
 */
@Ignore // Cassandra doesn't support negations or not in
class NotInListSpec extends GormDatastoreSpec {

    void "test not in list returns the correct results"() {
        when:
        new TestEntity(name:"Fred").save()
        new TestEntity(name:"Bob").save()
        new TestEntity(name:"Jack").save(flush:true)

        then:
        TestEntity.countByNameNotInList(['Fred', "Bob"]) == 1
        TestEntity.findByNameNotInList(['Fred', "Bob"]).name == "Jack"
    }
}
