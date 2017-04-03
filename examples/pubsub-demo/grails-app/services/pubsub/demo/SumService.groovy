package pubsub.demo

import grails.events.annotation.Publisher
import grails.gorm.transactions.Transactional

class SumService {

    @Publisher
    @Transactional
    int sum(int a, int b) {
        return a + b
    }
}
