#!/bin/bash
EXIT_STATUS=0

./gradlew --stop


./gradlew test --refresh-dependencies -no-daemon -x gorm-cassandra-spring-boot:test  || EXIT_STATUS=$?
if [[ $EXIT_STATUS -eq 0 ]]; then 
    ./gradlew gorm-cassandra-spring-boot:test --refresh-dependencies -no-daemon || EXIT_STATUS=$?
fi

./gradlew --stop

if [[ $EXIT_STATUS -eq 0 ]]; then
    ./travis-publish.sh || EXIT_STATUS=$?
fi

exit $EXIT_STATUS



