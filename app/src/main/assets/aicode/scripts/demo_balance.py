#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
AiCode balance query demo script (demo_balance.py) - pay-as-you-go Adaptive Card template

This script demonstrates how to return balance, usage statistics and quick console actions as Adaptive Card data for an AiCode provider.
"""

import json
import os
import sys

def main():
    # Simulate balance and usage detail data as returned by an API
    data = {
        "type": "AdaptiveCard",
        "version": "1.5",
        "compact": {
            "type": "Row",
            "spacing": "Medium",
            "items": [
                { "type": "StatusDot", "color": "Good" },
                { "type": "TextBlock", "text": "Available balance $18.42", "weight": "Bolder" },
                { "type": "TextBlock", "text": "(today $0.45)", "size": "Small", "isSubtle": True }
            ]
        },
        "body": [
            {
                "type": "ColumnSet",
                "columns": [
                    {
                        "type": "Column",
                        "width": "stretch",
                        "items": [
                            { "type": "TextBlock", "text": "Current available balance", "size": "Small", "isSubtle": True },
                            { "type": "TextBlock", "text": "$18.42", "size": "ExtraLarge", "weight": "Bolder", "color": "Good" },
                            { "type": "TextBlock", "text": "≈ ¥132.60 CNY", "size": "Small", "isSubtle": True }
                        ]
                    },
                    {
                        "type": "Column",
                        "width": "stretch",
                        "separator": True,
                        "items": [
                            { "type": "TextBlock", "text": "Total spent this month", "size": "Small", "isSubtle": True },
                            { "type": "TextBlock", "text": "$6.58", "size": "ExtraLarge", "weight": "Bolder", "color": "Accent" },
                            { "type": "TextBlock", "text": "Spent today $0.45", "size": "Small", "isSubtle": True }
                        ]
                    }
                ]
            },
            { "type": "Divider" },
            {
                "type": "FactSet",
                "facts": [
                    { "title": "Current billing mode", "value": "Official list price (no multiplier)" },
                    { "title": "Tokens remaining", "value": "12,450,000" },
                    { "title": "Rate limit", "value": "500 RPM" }
                ]
            }
        ],
        "actions": [
            {
                "type": "Action.OpenUrl",
                "title": "Management console",
                "url": "https://api.openai.com",
                "icon": "external-link"
            }
        ]
    }

    print(json.dumps(data, ensure_ascii=False))

if __name__ == "__main__":
    main()
