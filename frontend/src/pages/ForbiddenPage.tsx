import { Link } from 'react-router';

export function ForbiddenPage(): JSX.Element {
  return (
    <div className="state">
      <h1>Not allowed</h1>
      <p>This page is only available to administrators.</p>
      <Link to="/">Back to products</Link>
    </div>
  );
}

export function NotFoundPage(): JSX.Element {
  return (
    <div className="state">
      <h1>Page not found</h1>
      <Link to="/">Back to products</Link>
    </div>
  );
}
