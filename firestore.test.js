const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const ALICE_UID = "alice_trader_123";
const BOB_UID = "bob_trader_456";

const [emulatorHost, emulatorPortStr] = (process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085").split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

before(async () => {
  const rules = fs.readFileSync("./firestore.rules", "utf8");
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules,
      host: emulatorHost,
      port: emulatorPort,
    },
  });
});

after(async () => {
  if (testEnv) {
    await testEnv.cleanup();
  }
});

beforeEach(async () => {
  if (testEnv) {
    await testEnv.clearFirestore();
  }
});

function pastDate(msOffset = 2000) {
  return new Date(Date.now() - msOffset);
}

test("Unauthenticated user: cannot read or write any user collections", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).get());
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).collection("strategies").get());
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).collection("trades").get());
  await assertFails(
    unauthDb.collection("users").doc(ALICE_UID).set({
      userId: ALICE_UID,
      displayName: "Hacker",
      executionMode: "SIMULATION",
      simulationBalance: 10000,
      createdAt: pastDate(),
      updatedAt: pastDate(),
    })
  );
});

test("UserProfile: owner create, partial update, and cross-user denial", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const created = pastDate(10000);

  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).set({
      userId: ALICE_UID,
      displayName: "Alice Quant",
      email: "alice@example.com",
      executionMode: "SIMULATION",
      simulationBalance: 10000.0,
      createdAt: created,
      updatedAt: created,
    })
  );

  // Partial update preserving createdAt and userId
  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).update({
      simulationBalance: 10185.5,
      lastBackupAt: pastDate(1000),
      updatedAt: pastDate(1000),
    })
  );

  // Reject ghost field
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).update({
      isAdmin: true,
      updatedAt: pastDate(500),
    })
  );

  // Reject cross-user read or write
  await assertFails(bobDb.collection("users").doc(ALICE_UID).get());
  await assertFails(
    bobDb.collection("users").doc(ALICE_UID).update({
      simulationBalance: 0,
      updatedAt: pastDate(500),
    })
  );

  // Reject list on top-level users collection
  await assertFails(aliceDb.collection("users").get());
});

test("StrategyConfig: valid Kotlin create, partial update, list query, and @DocumentId guard", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const created = pastDate(10000);

  const validStrategy = {
    userId: ALICE_UID,
    name: "Momentum EMA-RSI",
    instrument: "EUR/USD",
    timeframe: "1m",
    tradeAmount: 25.0,
    expirySeconds: 60,
    rsiPeriod: 14,
    rsiOversold: 30,
    rsiOverbought: 70,
    emaFast: 9,
    emaSlow: 21,
    macdFast: 12,
    macdSlow: 26,
    macdSignal: 9,
    bbPeriod: 20,
    bbStdDev: 2.0,
    minConfidence: 68,
    active: true,
    createdAt: created,
    updatedAt: created,
  };

  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("strategies").doc("strat_1").set(validStrategy)
  );

  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("strategies").doc("strat_1").update({
      tradeAmount: 50.0,
      minConfidence: 72,
      updatedAt: pastDate(1000),
    })
  );

  // List subcollection as owner succeeds
  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("strategies").get()
  );

  // Reject @DocumentId `id` field in payload
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).collection("strategies").doc("strat_2").set({
      id: "strat_2",
      ...validStrategy,
    })
  );

  // Reject future timestamp
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).collection("strategies").doc("strat_3").set({
      ...validStrategy,
      createdAt: new Date(Date.now() + 60000),
    })
  );

  // Cross-user list and read fail
  await assertFails(bobDb.collection("users").doc(ALICE_UID).collection("strategies").get());
});

test("RiskPolicy: create, emergency stop update, and invalid limits rejection", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const created = pastDate(10000);

  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("riskPolicies").doc("default").set({
      userId: ALICE_UID,
      maxTradeAmount: 100.0,
      maxDailyLoss: 250.0,
      maxConsecutiveLosses: 3,
      maxDailyTrades: 30,
      maxPortfolioExposure: 500.0,
      cooldownMinutes: 15,
      emergencyStopped: false,
      autoTradeEnabled: false,
      createdAt: created,
      updatedAt: created,
    })
  );

  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("riskPolicies").doc("default").update({
      emergencyStopped: true,
      autoTradeEnabled: false,
      updatedAt: pastDate(1000),
    })
  );

  // Reject negative maxDailyLoss
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).collection("riskPolicies").doc("default").update({
      maxDailyLoss: -50.0,
      updatedAt: pastDate(500),
    })
  );
});

test("TradeRecord: OPEN trade settles to WON and locks against further updates", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const created = pastDate(10000);

  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("trades").doc("trade_1").set({
      userId: ALICE_UID,
      instrument: "EUR/USD",
      timeframe: "1m",
      direction: "CALL",
      mode: "SIMULATION",
      amount: 25.0,
      payoutRate: 0.85,
      entryPrice: 1.0845,
      exitPrice: 0.0,
      status: "OPEN",
      pnl: 0.0,
      strategyName: "Momentum EMA-RSI",
      confidenceScore: 76,
      calibratedProbability: 0.64,
      explanation: "RSI bullish bounce + EMA9 > EMA21",
      createdAt: created,
      updatedAt: created,
    })
  );

  // Transition OPEN -> WON
  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("trades").doc("trade_1").update({
      exitPrice: 1.0852,
      status: "WON",
      pnl: 21.25,
      updatedAt: pastDate(1000),
    })
  );

  // Terminal state locking: cannot modify WON trade
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).collection("trades").doc("trade_1").update({
      exitPrice: 1.0800,
      status: "LOST",
      pnl: -25.0,
      updatedAt: pastDate(500),
    })
  );
});

test("DailyPnLSummary: owner create, update, and dateKey parity check", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const created = pastDate(10000);

  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("dailySummaries").doc("2026-11-24").set({
      userId: ALICE_UID,
      dateKey: "2026-11-24",
      totalTrades: 5,
      wins: 4,
      losses: 1,
      netPnl: 60.0,
      consecutiveLosses: 0,
      peakDrawdown: 25.0,
      createdAt: created,
      updatedAt: created,
    })
  );

  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("dailySummaries").doc("2026-11-24").update({
      totalTrades: 6,
      wins: 5,
      netPnl: 81.25,
      updatedAt: pastDate(1000),
    })
  );

  // Mismatched dateKey vs doc ID must fail
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).collection("dailySummaries").doc("2026-11-25").set({
      userId: ALICE_UID,
      dateKey: "2026-11-24",
      totalTrades: 1,
      wins: 1,
      losses: 0,
      netPnl: 10.0,
      consecutiveLosses: 0,
      peakDrawdown: 0.0,
      createdAt: created,
      updatedAt: created,
    })
  );
});
