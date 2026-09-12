
# Architecture 26 - Demo

A demonstrative project for detaching POS client communication from Resto server.

## Background

A POS client could be the current RestoGo client, SoftPos client or the Spike project, RestoSnap. POS client is a PointOfSales application that needs configuration data and article price catalog data to operate (consumes). When a clerk or a customer interacts with POS application, the application creates receipt documents that need to be synchronzed back to server, e.g for reporting purposes (produces).

The goals this demo, by detaching the clients from direct communication with the server, is to demonstrate a model that:
- is highly fault tolerant
- is not impacted by peak work loads
- outsources critical uptime/load -sensitive parts
- is easy and cost efficient to scale
- is highly performant even under high loads
- uses trustworthy Nordic providers


## The Demonstrated Architecture

### Architectural Components

#### Backend

The server where the service customers (tenants) maintain their price catalogs. This is also where the support maintains, monitors and configures the fleet of POS clients.

For the same of this demo, the backend will be implemented as a Node application under the subdirectory: backend.

#### POS Client

An imaginary client application that:
a) consumes configuration and price catalog data
b) produces receipts and logging data

For the sake of this demo, this client will be implemented as a Java client application under a subdirectory: client.

#### Mediator Service

The mediator service is the component in this architecture that (mostly) detaches the direct communication between the client and the server. Some communication is still required to kick things off.

For the sake of this demo, this mediator service will be implmented by a S3 compatible object storage from UpCould.

## Demo development model

This demo will be generated using AI agents (you) and using SDD (Spec Driven Development). The specs are written under docs/spec. 

Since this is a demo project, we never need to worry about existing clients or backwards compatibility. Everything can be deleted and rewritten.