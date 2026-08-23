import { Component, type ErrorInfo, type ReactNode } from 'react'

interface ErrorBoundaryProps {
  children: ReactNode
}

interface ErrorBoundaryState {
  error: Error | null
}

/**
 * Last-resort crash guard: a render error in one pane must not blank the
 * whole app. Vault data is written by multiple writers (phone, pipeline,
 * Obsidian), so malformed API payloads reaching render logic are realistic.
 */
export class ErrorBoundary extends Component<ErrorBoundaryProps, ErrorBoundaryState> {
  state: ErrorBoundaryState = { error: null }

  static getDerivedStateFromError(error: Error): ErrorBoundaryState {
    return { error }
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    console.error('Unhandled render error:', error, info.componentStack)
  }

  render() {
    if (this.state.error) {
      return (
        <div
          role="alert"
          style={{
            padding: '2rem',
            fontFamily: 'monospace',
            whiteSpace: 'pre-wrap',
          }}
        >
          <h1>Something went wrong</h1>
          <p>{this.state.error.message}</p>
          <button type="button" onClick={() => this.setState({ error: null })}>
            Try again
          </button>
          <button
            type="button"
            onClick={() => window.location.reload()}
            style={{ marginLeft: '0.5rem' }}
          >
            Reload Chronicle
          </button>
        </div>
      )
    }
    return this.props.children
  }
}
