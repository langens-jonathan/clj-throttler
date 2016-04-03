# mu-clj-throttler
a REST API service that proxies to other REST API services but enforces request limits for users

## starting up the service

### first make sure a datomic db is running
start a datomic service, for more info see http://www.datomic.com

### start this service
lein ring server-headless