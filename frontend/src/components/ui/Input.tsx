'use client';

import React, { forwardRef, useId } from 'react';

interface InputProps extends React.InputHTMLAttributes<HTMLInputElement> {
  label?: string;
  error?: string;
  helperText?: string;
}

const Input = forwardRef<HTMLInputElement, InputProps>(({
  label,
  error,
  helperText,
  className = '',
  id,
  ...props
}, ref) => {
  const baseStyles = 'block w-full rounded-xl border border-line bg-surface px-3 py-2.5 text-ink shadow-sm placeholder:text-ink/40 focus:border-brass focus:outline-none focus:ring-2 focus:ring-brass transition-colors';
  const errorStyles = error ? 'border-red-400 focus:ring-red-500 focus:border-red-500' : '';
  const combinedClassName = `${baseStyles} ${errorStyles} ${className}`;
  const generatedId = useId();
  const inputId = id || generatedId;

  return (
    <div className="space-y-1">
      {label && (
        <label htmlFor={inputId} className="block text-sm font-medium text-ink">
          {label}
        </label>
      )}
      <input
        ref={ref}
        id={inputId}
        className={combinedClassName}
        {...props}
      />
      {error && (
        <p className="text-sm text-red-600">{error}</p>
      )}
      {helperText && !error && (
        <p className="text-sm text-gray-500">{helperText}</p>
      )}
    </div>
  );
});

Input.displayName = 'Input';

export default Input;
