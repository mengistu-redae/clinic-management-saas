import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { useClinicsDirectory, useCreateLabRequest } from '../../api/queries.js';
import ErrorBanner from '../../components/ErrorBanner.jsx';
import PageContainer from '../../components/PageContainer.jsx';
import PageHeader from '../../components/PageHeader.jsx';
import Button from '../../components/Button.jsx';
import { inputClass } from '../../components/Field.jsx';

/**
 * Patient self-service lab request - POST /api/my-lab-orders
 * (PatientLabRequestController.createRequest). No test-code picker here
 * (unlike the staff create form) - a patient names tests in plain language;
 * staff assigns real testCodes (and pricing) at confirm-and-order time,
 * mirroring the reference project's own request->confirm two-phase cargo
 * flow. No pricing shown - this is a request, not yet an order.
 */
export default function RequestLabTest() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const { data: clinics, isLoading: clinicsLoading } = useClinicsDirectory();
  const createRequest = useCreateLabRequest();

  const [clinicId, setClinicId] = useState('');
  const [testNames, setTestNames] = useState(['']);
  const [notes, setNotes] = useState('');
  const [formError, setFormError] = useState(null);

  function updateTestName(index, value) {
    setTestNames((names) => names.map((n, i) => (i === index ? value : n)));
  }
  function addTestName() {
    setTestNames((names) => [...names, '']);
  }
  function removeTestName(index) {
    setTestNames((names) => names.filter((_, i) => i !== index));
  }

  async function handleSubmit(event) {
    event.preventDefault();
    setFormError(null);
    const names = testNames.map((n) => n.trim()).filter(Boolean);
    if (!clinicId || names.length === 0) {
      setFormError(t('requestLabTest.errorPickClinic'));
      return;
    }
    try {
      const created = await createRequest.mutateAsync({ clinicId, testNames: names, notes: notes.trim() || undefined });
      navigate(`/my-lab-orders/${created.order.id}`);
    } catch (err) {
      setFormError(err.message || t('requestLabTest.errorSubmit'));
    }
  }

  return (
    <PageContainer width="xl">
      <PageHeader title={t('requestLabTest.title')} description={t('requestLabTest.subtitle')} />

      <form onSubmit={handleSubmit} className="flex flex-col gap-4 rounded-xl border border-slate-200 bg-surface p-4">
        <label className="block">
          <span className="mb-1 block text-xs font-semibold uppercase tracking-wide text-ink-muted">{t('requestLabTest.clinic')}</span>
          <select value={clinicId} onChange={(e) => setClinicId(e.target.value)} className={`${inputClass} w-full`} disabled={clinicsLoading}>
            <option value="">{clinicsLoading ? t('requestLabTest.loading') : t('requestLabTest.selectClinic')}</option>
            {(clinics || []).map((c) => (
              <option key={c.id} value={c.id}>{c.name}</option>
            ))}
          </select>
        </label>

        <div>
          <span className="mb-1 block text-xs font-semibold uppercase tracking-wide text-ink-muted">{t('requestLabTest.tests')}</span>
          <div className="flex flex-col gap-2">
            {testNames.map((name, i) => (
              <div key={i} className="flex items-center gap-2">
                <input
                  value={name}
                  onChange={(e) => updateTestName(i, e.target.value)}
                  placeholder={t('requestLabTest.testNamePlaceholder')}
                  className={`${inputClass} w-full`}
                />
                {testNames.length > 1 && (
                  <button type="button" onClick={() => removeTestName(i)} className="shrink-0 text-xs text-danger hover:underline">{t('labOrderTestsEditor.remove')}</button>
                )}
              </div>
            ))}
          </div>
          <button type="button" onClick={addTestName} className="mt-2 text-xs font-semibold text-brand-text hover:underline">{t('requestLabTest.addAnotherTest')}</button>
        </div>

        <label className="block">
          <span className="mb-1 block text-xs font-semibold uppercase tracking-wide text-ink-muted">{t('requestLabTest.notes')}</span>
          <textarea value={notes} onChange={(e) => setNotes(e.target.value)} rows={2} className={`${inputClass} w-full`} />
        </label>

        <Button type="submit" variant="accent" disabled={createRequest.isPending} className="self-start">
          {createRequest.isPending ? t('requestLabTest.submitting') : t('requestLabTest.submit')}
        </Button>
      </form>
      {formError && <div className="mt-4"><ErrorBanner message={formError} /></div>}
    </PageContainer>
  );
}
