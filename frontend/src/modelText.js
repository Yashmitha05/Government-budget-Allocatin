// Plain-language descriptions of model types, shared by every page that shows a forecast.
export const MODEL_TYPES = {
  naive_last: {
    name: 'Same as last year',
    explain: 'Next year equals the last actual year. In testing, no statistical model forecast this item more accurately than this simple rule.',
  },
  drift_3y: {
    name: 'Recent trend',
    explain: 'Continues the average growth of the last three years.',
  },
  ridge_core: {
    name: 'Statistical model (growth history)',
    explain: 'Learns from recent growth of this item in all states, plus national inflation.',
  },
  ridge_macro: {
    name: 'Statistical model (growth + state economy)',
    explain: 'Learns from recent growth, national inflation and growth of the state economy (GSDP), across all states.',
  },
  hgb_macro: {
    name: 'Machine-learning model (gradient boosting)',
    explain: 'A tree-based model using recent growth, inflation and the state economy.',
  },
};

export const modelName = (type) => MODEL_TYPES[type]?.name || type;
export const modelExplain = (type) => MODEL_TYPES[type]?.explain || '';
