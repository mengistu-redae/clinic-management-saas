import { useTranslation } from 'react-i18next';

const inputClass =
  'rounded-lg border border-slate-300 px-3 py-2 text-sm focus:border-brand focus:outline-none focus:ring-2 focus:ring-brand/20';

export const emptyTestLine = () => ({ testCode: '', testName: '', specimenType: '', notes: '' });

/**
 * A test-line-item editor shared by every lab-order form that submits a
 * `tests: TestItem[]` list (create, edit-while-ordered, confirm-and-order) -
 * same "reusable line-item editor" shape as the reference project's own
 * WaybillItemsEditor.jsx, this app's own field set (testCode/testName/
 * specimenType/notes) instead of description/quantity/weight.
 */
export default function LabOrderTestsEditor({ lines, onChange, labRates }) {
  const { t } = useTranslation();
  function updateLine(index, field, value) {
    onChange(lines.map((l, i) => (i === index ? { ...l, [field]: value } : l)));
  }
  function addLine() {
    onChange([...lines, emptyTestLine()]);
  }
  function removeLine(index) {
    onChange(lines.filter((_, i) => i !== index));
  }

  return (
    <div className="flex flex-col gap-2">
      {lines.map((line, i) => (
        <div key={i} className="flex flex-wrap items-end gap-3">
          <Field label={t('labOrderTestsEditor.testCode')}>
            <input
              list="lab-order-test-codes"
              value={line.testCode}
              onChange={(e) => {
                const code = e.target.value;
                const rate = (labRates || []).find((r) => r.testCode === code);
                updateLine(i, 'testCode', code);
                if (rate && !line.testName) updateLine(i, 'testName', code);
              }}
              placeholder="CBC"
              className={`${inputClass} w-32`}
            />
          </Field>
          <Field label={t('labOrderTestsEditor.testName')}>
            <input value={line.testName} onChange={(e) => updateLine(i, 'testName', e.target.value)} placeholder="Complete Blood Count" className={`${inputClass} w-56`} />
          </Field>
          <Field label={t('labOrderTestsEditor.specimen')}>
            <input value={line.specimenType} onChange={(e) => updateLine(i, 'specimenType', e.target.value)} placeholder="Blood" className={`${inputClass} w-32`} />
          </Field>
          <button type="button" onClick={() => removeLine(i)} className="text-xs text-danger hover:underline">
            {t('labOrderTestsEditor.remove')}
          </button>
        </div>
      ))}
      <button type="button" onClick={addLine} className="self-start text-xs font-semibold text-brand-text hover:underline">
        {t('labOrderTestsEditor.addTest')}
      </button>
      {labRates && labRates.length > 0 && (
        <datalist id="lab-order-test-codes">
          {labRates.map((r) => (
            <option key={r.testCode} value={r.testCode} />
          ))}
        </datalist>
      )}
    </div>
  );
}

function Field({ label, children }) {
  return (
    <label className="block text-left">
      <span className="mb-1 block text-xs font-semibold uppercase tracking-wide text-ink-muted">{label}</span>
      {children}
    </label>
  );
}
