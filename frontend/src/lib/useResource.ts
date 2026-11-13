import { useCallback, useEffect, useState, type DependencyList } from 'react';

export interface ResourceState<T> {
  data: T | null;
  error: unknown;
  loading: boolean;
  /** Re-runs the fetch with the same parameters. */
  reload: () => void;
}

/**
 * Loads one resource for the current parameters. Aborts the previous request when the
 * parameters change or the component unmounts, so a slow reply never overwrites a newer one.
 */
export function useResource<T>(
  fetcher: (signal: AbortSignal) => Promise<T>,
  deps: DependencyList,
): ResourceState<T> {
  const [data, setData] = useState<T | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [loading, setLoading] = useState(true);
  const [tick, setTick] = useState(0);
  // Fresh loads start with loading true; the reload path sets it when the tick changes.

  // The fetcher is recreated every render, so the caller's deps decide when to refetch.
  useEffect(() => {
    const controller = new AbortController();
    fetcher(controller.signal)
      .then((result) => {
        setData(result);
        setError(null);
      })
      .catch((cause: unknown) => {
        if (controller.signal.aborted) {
          return;
        }
        setError(cause);
      })
      .finally(() => {
        if (!controller.signal.aborted) {
          setLoading(false);
        }
      });
    return () => {
      controller.abort();
    };
  }, [...deps, tick]);

  const reload = useCallback(() => {
    setLoading(true);
    setTick((value) => value + 1);
  }, []);

  return { data, error, loading, reload };
}
