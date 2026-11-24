import { Buffer } from 'buffer'
import { host } from './host'

type SqlValue = string | number | null | Uint8Array
type WireValue = string | number | null | { blob: string }

function encode(value: SqlValue): WireValue {
  return value instanceof Uint8Array ? { blob: Buffer.from(value).toString('base64') } : value
}

function decode(value: WireValue): SqlValue {
  return value !== null && typeof value === 'object' ? Buffer.from(value.blob, 'base64') : value
}

class Statement {
  private rawRows = false

  constructor(private readonly sql: string) {}

  raw(): this {
    this.rawRows = true
    return this
  }

  all(...params: SqlValue[]): unknown[] {
    const { columns, rows } = host<{ columns: string[]; rows: WireValue[][] }>('sqlQuery', [this.sql, params.map(encode)])
    return rows.map((row) => this.rawRows ? row.map(decode) : Object.fromEntries(columns.map((key, i) => [key, decode(row[i])])))
  }

  get(...params: SqlValue[]): unknown {
    return this.all(...params)[0]
  }

  run(...params: SqlValue[]): { changes: number } {
    return { changes: host<number>('sqlRun', [this.sql, params.map(encode)]) }
  }
}

/** Synchronous SQLite driver used only inside the trusted Android service runtime. */
export default class AndroidSQLite {
  prepare(sql: string): Statement {
    return new Statement(sql)
  }

  exec(sql: string): void {
    // The shared migration source contains only DDL without quoted semicolons.
    for (const statement of sql.split(';')) {
      if (statement.trim()) host('sqlRun', [statement, []])
    }
  }

  pragma(pragma: string): unknown {
    if (pragma === 'user_version') return host<number>('sqlVersion')
    if (/^user_version = \d+$/.test(pragma)) return host('sqlRun', [`PRAGMA ${pragma}`, []])
    // Android configures WAL and foreign keys when it opens the database.
    if (pragma !== 'journal_mode = WAL' && pragma !== 'foreign_keys = ON') throw new Error('Unsupported PRAGMA')
  }

  transaction<A extends unknown[], R>(callback: (...args: A) => R) {
    const execute = (...args: A): R => {
      host('sqlBegin')
      try {
        const result = callback(...args)
        host('sqlCommit')
        return result
      } finally {
        host('sqlEnd')
      }
    }
    return Object.assign(execute, { deferred: execute, immediate: execute, exclusive: execute })
  }
}
