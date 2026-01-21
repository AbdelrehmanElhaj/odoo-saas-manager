#!/bin/bash
mvn clean package -DskipTests
docker build -t odoo-saas-manager:latest .
