# Vault Economy 1.7 — API Snapshot

**Source:** milkbowl/vaultapi (context7) + VaultAPI 1.7
**Pinned version:** `com.github.MilkBowl:VaultAPI:1.7`
**Snapshot date:** 2026-05-24

## Key Classes / Interfaces

| Class | Package | Role |
|---|---|---|
| `Economy` | `net.milkbowl.vault.economy` | Economy service interface |
| `EconomyResponse` | `net.milkbowl.vault.economy` | Result wrapper for econ operations |

## Critical Signatures — `Economy` interface

```java
// Account management
boolean hasAccount(Player player);
boolean hasAccount(String playerName);
boolean createPlayerAccount(Player player);
boolean createPlayerAccount(String playerName);

// Balance (all return double; amounts in major units e.g. 1.0 = 1 coin)
double getBalance(Player player);
double getBalance(String playerName);

// Withdraw (returns EconomyResponse)
EconomyResponse withdrawPlayer(Player player, double amount);
EconomyResponse withdrawPlayer(String playerName, double amount);

// Deposit
EconomyResponse depositPlayer(Player player, double amount);
EconomyResponse depositPlayer(String playerName, double amount);

// Format
String format(double amount);                         // e.g. "1.05 coins"
boolean has(Player player, double amount);            // convenience: balance >= amount
```

## `EconomyResponse` fields

```java
class EconomyResponse {
    double amount;            // amount passed in
    double balance;           // new balance after operation
    EconomyResponse.ResponseType type;  // SUCCESS or FAILURE
    String errorMessage;      // set when type == FAILURE
}

enum ResponseType { SUCCESS, FAILURE }
```

## Service registration (for reference)

```java
RegisteredServiceProvider<Economy> rsp =
    server.getServicesManager().getRegistration(Economy.class);
Economy econ = rsp.getProvider(); // null if no econ plugin
```

## EnthusiaMarket Usage Notes

- **All money in EnthusiaMarket is integer minor units** (whole coins). `VaultEconomyProvider` converts at the boundary: `vaultAmount = round(domainAmount)`. The domain layer never sees `double`.
- `EconomyResponse.transactionSuccess()` maps to `EconomyResponse.type == ResponseType.SUCCESS`.
- If `econ == null` at `onEnable`, rent collection, sign shops, and auctions are disabled (REQ-041).

## Breaking-Change Watchpoints

1. `EconomyResponse.balance` is `double` — precision loss possible for very large values. EnthusiaMarket mitigates by using only integer math in domain.
2. `getBalance` / `withdrawPlayer` accept `String playerName` (deprecated in newer APIs) — this project uses UUID-based lookups via `Player` objects.

## Evidence

- context7:/milkbowl/vaultapi — full Economy interface, EconomyResponse, service setup
- com.github.MilkBowl:VaultAPI:1.7 (pinned in build.gradle.kts:22)
