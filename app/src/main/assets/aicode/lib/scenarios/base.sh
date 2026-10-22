#!/bin/sh
# Scenario: base tools only (bash curl ripgrep git)
scenario_name_base() { echo "Base tools only (bash curl ripgrep git)"; }
install_scenario_base() { install_light_runtimes ""; }