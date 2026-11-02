# Project Guide: State Budget Intelligence

**Adaptive AI-Based Government Budget Intelligence and Decision Support System for Predictive, Counterfactual and Resilient Budget Planning**

This guide explains the whole project in plain language, for team members and faculty. Read it top to bottom once. After that, use the headings to jump to what you need. The other `.md` files go deeper into one topic each; they are listed at the end.

---

## 1. What the project does, in one paragraph

Every Indian state presents a budget each year: how much it expects to collect (taxes and other revenue), how much it will spend, and how much it will borrow. Our system takes **real published budget data for about 30 Indian states from 2004-05 to 2024-25** and helps a planner do five things:

- see the budget clearly;
- **predict** next years' revenue and spending;
- try **"what if" changes** to how money is split between sectors;
- find the **best split** that still obeys the legal borrowing limit;
- check whether the budget would **survive bad years** (falling revenue, high inflation, an economic slowdown).

It also **keeps every prediction it makes**, compares each one with the real figure when that is published, and **uses its own mistakes to improve the model**. That is the "adaptive" part of the title.

---

## 2. Where the data comes from (all real, nothing made up)

| Source | What we took | Why |
|---|---|---|
| **Reserve Bank of India: *Handbook of Statistics on Indian States 2024-25*** (released 11 Dec 2025) | 24 official tables: revenue expenditure, capital expenditure, capital outlay, social-sector spending, health spending, own tax revenue, non-tax revenue, interest, pension, fiscal deficit, revenue deficit, state GDP (GSDP), per-capita income, inflation, infant mortality, school enrolment, unemployment, road length, hospitals, doctors | This is the official, consistent, state-by-state record of budgets and the economy |
| **World Bank: World Development Indicators** | India's national inflation, GDP, GDP growth, population, population growth, revenue as % of GDP | National background, and used to size "bad year" shocks |

- **Size after cleaning:** 14,730 data points, arranged as 829 rows, where each row is one state in one year.
- **Time span:** 2004-05 to 2024-25. State GDP starts in 2011-12.
- **Status of every number is kept:**
  - Up to 2021-22: **Accounts**, the audited actual figures.
  - 2022-23: **Actual**.
  - 2023-24: **RE (Revised Estimate)**, the state's own updated guess.
  - 2024-25: **BE (Budget Estimate)**, the state's plan.
  The ML models only ever **learn from actual figures**, never from the government's guesses.
- **Nothing is invented or filled in.** If a value is missing in the RBI table (for example, Telangana before it was formed in 2014), it stays missing.

Full details: [`DATASET_SELECTION.md`](DATASET_SELECTION.md).

---

## 3. The big picture: how the parts talk to each other

```
 ┌──────────────────────┐        ┌──────────────────────┐        ┌──────────────────────┐
 │  Website (React)     │ ─────▶ │  Backend (Spring     │ ─────▶ │  ML service (Python  │
 │  what the user sees  │ ◀───── │  Boot, Java)         │ ◀───── │  FastAPI)            │
 │  port 5173           │        │  port 8080           │        │  port 8000           │
 └──────────────────────┘        └──────────┬───────────┘        └──────────┬───────────┘
                                            │                               │
                                  ┌─────────▼─────────┐           ┌─────────▼──────────┐
                                  │  PostgreSQL       │           │  data/ (RBI files)  │
                                  │  database         │           │  models/ (trained)  │
                                  └───────────────────┘           └────────────────────┘
```

- **Website (frontend):** pages, charts and forms. It only talks to the backend.
- **Backend:** the "manager". It receives requests from the website, checks them, saves everything in the database (budget records, scenarios, predictions, errors, model versions), and asks the ML service whenever a calculation is needed.
- **ML service:** the "brain". It reads the RBI data, trains the models, and does the maths: forecasts, scenarios, optimisation, stress tests, and error checking.
- **Database (PostgreSQL):** the system's memory. It is what makes the feedback loop possible, because every prediction is stored and can be checked later.

**Example: what happens when you open the Dashboard for Karnataka**

1. The website asks the backend for Karnataka's summary.
2. The backend asks the ML service for Karnataka's latest budget split, and for a forecast of revenue expenditure and tax revenue.
3. The ML service loads the trained model, makes the forecast and sends it back.
4. The backend **saves each forecast with a Prediction ID**, so it can be checked later. It also compares the forecast with Karnataka's own revised and budget estimates, then sends everything to the website.
5. The website draws the numbers, the borrowing gauge and the charts.

---

## 4. Folder structure

Only the folders you need to know about are shown. Libraries that are downloaded automatically (`ml-service/.venv`, `frontend/node_modules`) and build output (`backend/target`) are left out.

```
JavaProject/
│
├── PROJECT_GUIDE.md            ← this file (start here)
├── README.md                   short summary + how to run
├── DATASET_SELECTION.md        which datasets, why, what's in them
├── SYSTEM_ARCHITECTURE.md      how the parts fit together
├── DATABASE_DESIGN.md          every database table and column
├── API_DOCUMENTATION.md        every web address (endpoint) the parts use
├── ML_PIPELINE.md              the ML steps in technical detail
├── MODEL_EVALUATION.md         accuracy results, with real numbers
├── TESTING.md                  what was tested and the results
├── .env.example                template for local settings (copy to .env)
│
├── data/
│   ├── raw/
│   │   ├── rbi_hsis_2024_25/   the 24 original RBI Excel tables + MANIFEST.csv (source links)
│   │   └── world_bank/         World Bank data saved as JSON
│   └── processed/              cleaned data made by the pipeline:
│                                 observations.csv (one value per row),
│                                 panel.csv (one row per state per year),
│                                 quality_report.json (all data checks),
│                                 dataset_metadata.json (file fingerprints)
│
├── ml-service/                 PYTHON: data pipeline, ML and maths
│   ├── budget_ml/              the core library
│   │   ├── ingest/             reads the RBI Excel files and World Bank data
│   │   ├── preprocessing.py    cleans, checks and joins everything into one table
│   │   ├── forecasting/        features, models, backtesting, training, live forecasts
│   │   ├── impact.py           "does spending improve outcomes?" analysis
│   │   ├── budget.py           splits a budget into sectors; deficit arithmetic
│   │   ├── scenario.py         what-if budget scenarios
│   │   ├── optimize.py         best allocation under limits
│   │   ├── stress.py           bad-year stress tests
│   │   ├── feedback.py         prediction errors, bias correction, retraining
│   │   └── registry.py         saves and loads model versions (v1, v2, ...)
│   ├── app/                    the FastAPI web service around the library
│   ├── scripts/                one-click jobs: build dataset, train models, calibrate
│   ├── models/                 the trained models, one folder per forecast item, v1/v2...
│   ├── reports/                backtest results (every past test prediction)
│   └── tests/                  131 automated tests
│
├── backend/                    JAVA (Spring Boot): the manager + database
│   └── src/main/java/com/budgetintel/
│       ├── controller/         receives web requests (the API)
│       ├── service/            the actual work: import, forecasts, scenarios, feedback loop
│       ├── repository/         reads and writes the database
│       ├── entity/             Java versions of the database tables
│       ├── dto/                shapes of the data sent to and from the website
│       ├── client/             talks to the ML service
│       └── exception/          turns every error into a clear message
│   └── src/main/resources/db/migration/   SQL that creates the database tables
│
└── frontend/                   REACT: the website
    └── src/
        ├── pages/              one file per page (Dashboard, Scenario Simulator, ...)
        ├── components/         shared pieces: charts, borrowing gauge, cards
        └── api.js              how the website calls the backend
```

---

## 5. How each project objective is met

For each objective: **what it means**, **how we did it**, **where to see it**, and an **honest status**.

### Objective 1: Develop an AI-based system for government budget forecasting and analysis
- **How:** a complete three-part system (website, backend, ML service) built on official RBI data. The "AI" is machine-learning models that learn how state budgets grow from 30 states' history (section 6). The analysis covers year-by-year comparison, sector splits and deficit tracking.
- **See it:** the **Dashboard** and **Budget Analysis** pages.
- **Status:** ✅ Done.

### Objective 2: Predict future revenue, expenditure and departmental requirements
- **How:** five separately trained forecast models.
  - **Revenue:** own tax revenue and own non-tax revenue.
  - **Expenditure:** revenue expenditure (day-to-day spending) and capital expenditure (assets).
  - **Departmental requirement:** social-sector expenditure, which covers the education, health, welfare and housing departments together.
  Each forecast covers up to 3 years ahead and comes with a *likely range*, not just one number.
- **See it:** the Dashboard forecast charts. Every forecast is also listed on **Prediction Accuracy**.
- **Status:** ✅ Done, at **sector level**. The RBI publishes state budgets by sector (social, capital, and so on), not department by department, so "departmental requirements" are forecast as sector requirements. We did not invent department-level numbers the data does not contain.

### Objective 3: Simulate alternative budget allocation scenarios
- **How:** the Scenario Simulator moves money between three sectors (social sector, capital outlay, other spending).
  - **Presets:** Balanced, Health priority, Education & social priority, Infrastructure priority.
  - **Custom:** sliders for your own percentage changes.
  For each scenario it recalculates borrowing exactly and checks the 3% limit. Every scenario is saved, and saved scenarios can be compared side by side.
- **See it:** the **Scenario Simulator** page.
- **Status:** ✅ Done.

### Objective 4: Optimise budget allocation under financial constraints
- **How:** a mathematical optimiser (SciPy) searches for the best split while obeying these limits:
  - the **legal borrowing limit**: the fiscal deficit must stay within **3% of state GDP**, under the FRBM Act;
  - each sector staying within the range the state has actually used in the past;
  - an optional **minimum per-person social spending**.
  There are two goals: *smallest change that meets the rules*, or *maximise infrastructure spending*. If the limits are impossible to meet together, it says so and explains why.
- **Real example:** Punjab's 2024-25 budget borrows 3.63% of GSDP, so the optimiser finds the smallest change that brings it to exactly 3.00%.
- **See it:** the **Optimization** page.
- **Status:** ✅ Done.

### Objective 5: Evaluate budget resilience under different economic conditions
- **How:** stress tests apply five kinds of bad year. The size of each shock comes from the **state's own worst year on record**, not a guess:
  - revenue shortfall;
  - high inflation;
  - faster population growth;
  - economic downturn;
  - all of these combined.
  The page shows how borrowing would change, whether the 3% limit would be broken, and how much would need to be cut to recover.
- **Real example:** if Karnataka had its worst growth year again (2020-21), borrowing would rise from 2.88% to 3.73% of GSDP.
- **See it:** the **Stress Testing** page. It can test the current budget, a saved scenario or an optimised plan.
- **Status:** ✅ Done.

### Objective 6: Assess the governance impact of different budget allocations
- **How:** "governance impact" is measured through public-service results that are actually published for each state: infant mortality (health), road length (infrastructure) and unemployment (jobs). We used a statistical method (a panel regression with state and year effects) to test whether states that spent more in a sector later saw better results.
- **Finding:** in this data, **none of these links is statistically reliable**. The results move too little from year to year to detect an effect. The system therefore **shows the estimates honestly, marked "not measurable"**, instead of inventing a predicted improvement. No official state-level "governance score" exists in the data, so we did not make one up.
- **See it:** the "Effect on public services" box on the Scenario Simulator, and the table on **Data & Methods**.
- **Status:** ⚠️ **Done as an honest measurement**. The analysis is fully built and runs on real data. Its correct conclusion is that the data cannot yet measure the effect, and the system automatically starts showing effects if future data supports them.

### Objective 7: Compare predicted and actual outcomes
- **How:** every prediction is stored with a **Prediction ID**, the model version that made it, and the year it is for. When the real figure is available, the system records the **predicted value, actual value, error amount and % error**.
  - It holds **2,235 real comparisons** from historical tests: models trained only on earlier years, then checked against what actually happened.
  - The latest forecasts are also compared with each state's own revised and budget estimates.
- **See it:** the **Prediction Accuracy** page: a predicted-vs-actual chart, an error table, and a records list.
- **Status:** ✅ Done.

### Objective 8: Use prediction deviations for adaptive model improvement
- **How:** this is the **feedback loop**.
  1. The system looks at past errors for each state, for example "we predicted 10% too low for this state".
  2. It creates a **bias-corrected model version (v2)** that adjusts for those errors.
  3. It **only switches to v2 if v2 is more accurate on recent years it has not seen**. Otherwise v2 is stored but not used.
  4. When a new RBI edition with new actual figures is loaded, all stored predictions are checked automatically, and the models can be **retrained** into new versions.
- **Real result:**

  | Forecast item | Error before → after correction | Used? |
  |---|---|---|
  | Social-sector spending | 11.15% → 7.24% | ✅ yes (v2) |
  | Own tax revenue | 17.98% → 15.53% | ✅ yes (v2) |
  | Capital expenditure | 19.91% → 17.10% | ✅ yes (v2) |
  | Revenue expenditure | 4.56% → 4.61% | ❌ no (it got worse, so v1 is kept) |
  | Non-tax revenue | 27.55% → 29.49% | ❌ no (it got worse, so v1 is kept) |

- **See it:** **Prediction Accuracy → Model versions**, which has the Retrain and Correct bias buttons.
- **Status:** ✅ Done.

---

## 6. How the machine-learning training works (simple explanation)

### 6.1 What are we predicting?
For each state and each budget item (for example Karnataka's revenue expenditure), we predict **next year's value**.

The models predict the **growth rate** rather than the amount itself, because states differ enormously in size: Uttar Pradesh's budget is about 50 times Sikkim's. "Next year will be 8% higher" means the same thing for every state, so one model can learn from **all 30 states at once**. That gives it far more examples to learn from.

### 6.2 What information does the model use? (the "features")
For a given state and year, the model looks at:
- growth of this budget item **last year**, and **the year before**;
- **average growth over the last three years**;
- **India's inflation** that year;
- **growth of the state's economy (GSDP)** that year;
- the **size of this budget item compared with the state's economy**.

### 6.3 Which models compete?
We do not just pick one model. **Five candidates compete**, and the data decides:

| Candidate | In simple words |
|---|---|
| Same as last year | Assume no change. The simplest possible rule, and the yardstick. |
| Recent trend | Assume it keeps growing at its 3-year average. |
| Ridge regression (history) | A statistical model that learns a formula from the growth history and inflation. |
| Ridge regression (+ economy) | The same, but also uses how fast the state's economy is growing. |
| Gradient boosting | A machine-learning model made of many small decision trees. |

A learned model is only chosen if it **beats both simple rules**. We are not allowed to say "the AI is better" unless the numbers prove it.

### 6.4 How is fairness guaranteed? (no cheating with future data)
This is called a **rolling backtest**. We pretend to stand in each past year and predict the next one:

```
 Train on years up to 2014-15  →  predict 2015-16  →  compare with the real 2015-16
 Train on years up to 2015-16  →  predict 2016-17  →  compare with the real 2016-17
 ...
 Train on years up to 2021-22  →  predict 2022-23  →  compare with the real 2022-23
```

The model never sees the year it is predicting, exactly as in real life. The test years are split into two groups:
- **Selection years** (forecasts for 2015-16 to 2020-21): used to **choose** the best model.
- **Hold-out years** (forecasts for 2021-22 and 2022-23): **kept locked away** and used only at the end to report honest accuracy.

### 6.5 Training the final model
The winning model is trained again on all actual data (target years 2008-09 to 2022-23) and saved as **version 1** in `ml-service/models/<item>/v1/`. Its record includes when it was trained, on which data, its settings and its accuracy.

The **likely range** shown on charts comes from the model's own past errors: in testing, about **8 out of 10** real outcomes fell inside a range that wide.

### 6.6 Results (one-year-ahead error on the locked hold-out years)

| Forecast item | Method chosen | Typical error | "Same as last year" error |
|---|---|---|---|
| Revenue expenditure | Ridge regression (+ economy) | **4.6%** | 10.4% |
| Social-sector expenditure | Same as last year, bias-corrected | **7.2%** | 11.2% |
| Own tax revenue | Same as last year, bias-corrected | **15.5%** | 18.0% |
| Capital expenditure | Same as last year, bias-corrected | **17.1%** | 19.9% |
| Own non-tax revenue | Same as last year | **27.5%** | 27.5% |

**Why does "same as last year" win for four items?** The selection years include two unusual events: GST (2017), which froze state tax collections for a few years, and COVID-19 (2020-21). In those years "no change" was hard to beat. We fixed the selection rule *before* looking at the results and kept to it, which is the scientifically honest choice. The feedback loop (objective 8) then corrected part of the resulting error. When the next RBI edition adds 2023-24 actual figures, the same rule runs again on newer years.

### 6.7 How to run the training yourself
From the `ml-service` folder, with its Python environment active:

```bash
python scripts/build_dataset.py      # 1. read the RBI files → clean data in data/processed/
python scripts/train_models.py       # 2. backtest all candidates, choose, train, save v1
python scripts/calibrate_models.py   # 3. feedback loop: bias-correct, save v2 only if better
python -m pytest -q                  # 4. run all 131 automated checks
```

---

## 7. The pages of the website

| Page | What a user does there |
|---|---|
| **Dashboard** | See one state's latest budget: total spending, tax revenue, borrowing against the 3% limit, how the money is split, and forecasts for coming years |
| **Budget Analysis** | Pick any year: see the split, the change from the year before, and any indicator as a chart over time |
| **Scenario Simulator** | Try a preset or move sliders; see the effect on borrowing; save and compare scenarios |
| **Optimization** | Choose a goal and limits; get a recommended split and what limited it |
| **Stress Testing** | Test the current budget, a scenario or an optimised plan against bad years |
| **Prediction Accuracy** | See how accurate forecasts have been, every prediction record, and the model versions |
| **Data & Methods** | Data sources, methods and limitations |

Use the **State** menu at the top to switch between states. Every page updates for the chosen state.

---

## 8. How to run the whole project

You need PostgreSQL, Java 17 with Maven, Python 3, and Node.js installed. Start the parts in this order, each in its own terminal:

```bash
# 1. ML service (from ml-service/)
.venv/Scripts/python.exe -m uvicorn app.main:app --port 8000

# 2. Backend (from backend/) - creates the database tables automatically on first run
mvn spring-boot:run

# 3. Website (from frontend/)
npm run dev
```

Open **http://localhost:5173**. The first time, select **Load RBI dataset** in the top bar; this takes about a minute. Database settings go in a `.env` file in the project root; copy `.env.example` and fill in your PostgreSQL password.

---

## 9. Testing (summary)

| Part | Automated tests | Result |
|---|---|---|
| ML service (Python) | 131 | All pass |
| Backend (Java) | 23, plus 6 end-to-end tests that run the whole chain | All pass |
| Website (React) | 10 | All pass |

The whole system was also checked by hand in a browser, page by page, on the real data, including what happens when the ML service is switched off (the website shows a clear message instead of crashing). Details: [`TESTING.md`](TESTING.md).

---

## 10. Words you will hear (glossary)

| Term | Meaning |
|---|---|
| **₹ crore / lakh crore** | 1 crore = 10 million rupees. 1 lakh crore = 100,000 crore |
| **GSDP** | Gross State Domestic Product: the size of a state's economy |
| **Fiscal deficit (GFD)** | How much the state borrows in a year to pay for its budget |
| **FRBM 3% limit** | The Fiscal Responsibility and Budget Management rule: borrowing should stay within 3% of GSDP |
| **Revenue expenditure** | Day-to-day spending: salaries, pensions, interest, schemes, subsidies |
| **Capital outlay** | Spending on assets: roads, irrigation, buildings |
| **Social sector** | Education, health, family welfare, water, housing, welfare of weaker sections |
| **Own tax revenue** | Taxes the state collects itself (its GST share, stamp duty, excise, vehicle tax) |
| **Accounts / Actual** | Audited real figures |
| **RE / BE** | Revised Estimate / Budget Estimate: the state government's own projections |
| **Backtest** | Testing a model on past years as if they were the future |
| **MAPE / typical error** | Average size of the forecast error, in percent |
| **Bias** | A model that is consistently too high or too low |
| **Model version** | A saved, numbered copy of a trained model (v1, v2, ...) with its accuracy record |
| **Feedback loop** | Predict → compare with actual → learn from errors → improve the model |

---

## 11. Questions faculty may ask

**Is any data synthetic or made up?**
No. All figures come from the RBI Handbook and the World Bank, and the source links are in `data/raw/rbi_hsis_2024_25/MANIFEST.csv`. Missing values are left missing.

**Where exactly is the "AI"?**
The forecasting models in `ml-service/budget_ml/forecasting/`. They are chosen by a fair competition against simple rules, with accuracy measured on locked hold-out years. The optimiser and the statistical impact analysis are also data-driven.

**Why is the AI not always the winner?**
Because we only claim what the numbers support. For revenue expenditure the learned model clearly wins (4.6% error against 10.4%). For the others, unusual years such as GST and COVID made "no change" hard to beat, and we report that honestly.

**What is new in this project?**
The **closed feedback loop**. Predictions are stored, checked against reality, used to correct the model, and a new version replaces the old one only if it proves more accurate. All of this is combined with scenarios, constrained optimisation and data-sized stress tests on real Indian state data.

**What are the limitations?**
- About 30 states over 14–21 years is a small dataset.
- Education spending is not published separately by state.
- Health spending is only published up to 2019-20.
- No reliable spending-to-outcome link was found in this data.
- No official governance index exists at state level.

---

## 12. Other documents

| File | Read it when you need |
|---|---|
| [`README.md`](README.md) | A one-page summary and run commands |
| [`DATASET_SELECTION.md`](DATASET_SELECTION.md) | Which datasets were considered and why these were chosen |
| [`SYSTEM_ARCHITECTURE.md`](SYSTEM_ARCHITECTURE.md) | The technical design of the parts |
| [`DATABASE_DESIGN.md`](DATABASE_DESIGN.md) | Every table and column |
| [`API_DOCUMENTATION.md`](API_DOCUMENTATION.md) | Every endpoint, with request and response examples |
| [`ML_PIPELINE.md`](ML_PIPELINE.md) | The exact ML procedure |
| [`MODEL_EVALUATION.md`](MODEL_EVALUATION.md) | All accuracy numbers |
| [`TESTING.md`](TESTING.md) | What was tested, how, and the results |
