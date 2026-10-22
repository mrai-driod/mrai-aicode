# Token Statistics & Costs

"Settings → Token Stats" records the consumption of every AI call, for viewing token usage, costs, and cache hit rates.

Data is recorded from when this feature launched; historical calls before that have no details. The page refreshes in real time.

## Period Switching & Dimension Filtering

Four tabs at the top: Today / Last 7 days / Last 30 days / All. After switching, all cards, charts, and lists below update in sync. The trend chart for "Today" is shown by hour; the others by day.

Below the periods is a dimension filter bar:
- **Provider filter**: dropdown to select "All providers" or a specific provider.
- **Model filter**: dropdown to select "All models" or a specific model.
- **Clear filters**: appears after a filter is activated; one tap resets to all data.
- Additionally, clicking any row in "Provider Stats" or "Model Stats" quickly sets that item as the filter condition; click again to cancel.

## Overview

Four cards: total consumption (input plus output), request count, cost, cache hit rate.

Cost is an **estimate** — it preferentially uses the custom unit price you filled in under "Provider → Models", and only falls back to models.dev's public unit prices for those not filled in: cache reads are priced at the cache price, or estimated at 10% of the input price when there's no cache price; cache writes are priced at the cache write price, or estimated at 1.25× the input price when there is none. The total cost applies unit prices per "channel + model" and then sums, so the same model called from different providers is priced separately. The cache hit rate is cache reads divided by total input; below the card are this period's cache read and write token amounts.

::: tip Costs are for reference only
This is an estimate based on public unit prices, not a bill. When a relay station has changed prices, offers discounts, or your model has no unit price data, the estimate won't match the actual charge. Refer to the bill in the provider's console.
:::

## Consumption Trend

A line chart shows three lines over the period — input, output, cache hits (blue input, orange output, purple cache) — with translucent fill below the lines to help distinguish overlapping parts. The left axis is token count (abbreviated with k / M).

The time axis is fully expanded by period, with days or hours that have no records filled with 0, so the trend is visible normally even when calls are concentrated on a single day.

## Channel & Model Statistics

**Channel stats** aggregate by provider, showing the channel name, the number of configured key credentials, success rate percentage and a horizontal progress bar, with the request count and token consumption highlighted on the right, sorted by total consumption descending.

**Model stats** aggregate by model; each row shows the model name, input/output consumption, call count, and success rate, sorted by total consumption descending.

Clicking any channel or model row quickly applies it as a filter condition, linking the whole page's data.

## Call Records

A detail table of 10 entries per page, with pagination at the bottom; swipe left/right to see all fields:

| Field | Description |
| --- | --- |
| Call time | Year, month, day, hour, minute |
| Channel / Model | Which provider and model were used |
| Thinking intensity | This call's tier; models without tiers show `-` |
| Status | success / error / cancelled |
| Input / Output / Cache read / Cache write | Each type of token count |
| Cache hit rate | Cache input divided by total input |
| First-token latency | Time from request sent to the first content event (text, thinking, or tool call result) arriving |
| Total duration | Total time of this call |
| Estimated cost | Models without unit price data show `-` |

Rounds of pure tool calls also count first-token latency.

## Reset

The trash icon at the top right of the page clears all call records, and the statistics return to zero. A confirmation dialog pops up — **after clearing, it cannot be recovered**.
