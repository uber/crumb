#!/usr/bin/env bash

echo "Cloning osstrich..."
mkdir tmp
cd tmp
git clone git@github.com:square/osstrich.git
cd osstrich
echo "Packaging..."
mvn package
echo "Running..."
rm -rf tmp/crumb && java -jar target/osstrich-cli.jar tmp/crumb git@github.com:uber/crumb.git com.uber.crumb
echo "Cleaning up..."
cd ../..
rm -rf tmp
echo "Finished!"
