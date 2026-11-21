import React from 'react';

interface PageHeaderProps {
  eyebrow?: string;
  title: string;
  description?: string;
  actions?: React.ReactNode;
}

const PageHeader: React.FC<PageHeaderProps> = ({ eyebrow, title, description, actions }) => {
  return (
    <div className="mb-8 flex flex-col gap-4 sm:flex-row sm:items-end sm:justify-between">
      <div>
        {eyebrow && (
          <p className="text-xs font-medium uppercase tracking-[0.18em] text-brass">{eyebrow}</p>
        )}
        <h1 className="mt-1 text-3xl font-semibold tracking-tight text-ink">{title}</h1>
        {description && <p className="mt-2 max-w-2xl text-sm text-ink/70">{description}</p>}
      </div>
      {actions && <div className="flex flex-wrap gap-3">{actions}</div>}
    </div>
  );
};

export default PageHeader;
