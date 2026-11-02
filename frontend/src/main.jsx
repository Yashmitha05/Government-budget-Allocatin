import React from 'react';
import ReactDOM from 'react-dom/client';
import { BrowserRouter, Route, Routes } from 'react-router-dom';
import './styles.css';
import { AppProvider } from './AppContext';
import Layout from './components/Layout';
import Dashboard from './pages/Dashboard';
import BudgetAnalysis from './pages/BudgetAnalysis';
import ScenarioSimulator from './pages/ScenarioSimulator';
import Optimization from './pages/Optimization';
import StressTesting from './pages/StressTesting';
import PredictionAccuracy from './pages/PredictionAccuracy';
import Methodology from './pages/Methodology';
import { Empty } from './components/ui';

ReactDOM.createRoot(document.getElementById('root')).render(
  <React.StrictMode>
    <BrowserRouter future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>
      <AppProvider>
        <Routes>
          <Route element={<Layout />}>
            <Route index element={<Dashboard />} />
            <Route path="budget" element={<BudgetAnalysis />} />
            <Route path="scenarios" element={<ScenarioSimulator />} />
            <Route path="optimization" element={<Optimization />} />
            <Route path="stress-testing" element={<StressTesting />} />
            <Route path="accuracy" element={<PredictionAccuracy />} />
            <Route path="methodology" element={<Methodology />} />
            <Route path="*" element={<Empty title="Page not found">Use the menu on the left to choose a section.</Empty>} />
          </Route>
        </Routes>
      </AppProvider>
    </BrowserRouter>
  </React.StrictMode>,
);
