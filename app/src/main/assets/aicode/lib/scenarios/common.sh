#!/bin/sh
# Scenario: general development (base tools + Node.js + Python 3)
scenario_name_common() { echo "General development (base tools + Node.js + Python 3)"; }
install_scenario_common() { install_light_runtimes "node python"; }