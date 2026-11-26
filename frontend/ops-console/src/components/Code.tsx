import { Fragment } from 'react'

type Token = { c: string; t: string }

/** Splits one line of XML into coloured tokens (tags, attributes, strings, text) */
function tokenize(line: string): Token[] {
  const re = /(<\?[^?]*\?>)|(<\/?)([\w:.]+)|(\s[\w:]+)(=)("[^"]*")|(\/?>)|([^<]+)/g
  const ws = line.match(/^\s*/)?.[0] ?? ''
  const toks: Token[] = ws ? [{ c: '', t: ws }] : []
  let m: RegExpExecArray | null
  const rest = line.slice(ws.length)
  while ((m = re.exec(rest)) !== null) {
    if (m[1]) toks.push({ c: 'x-cmt', t: m[1] })
    else if (m[2]) toks.push({ c: 'x-p', t: m[2] }, { c: 'x-tag', t: m[3] })
    else if (m[4]) toks.push({ c: 'x-attr', t: m[4] }, { c: 'x-p', t: m[5] }, { c: 'x-str', t: m[6] })
    else if (m[7]) toks.push({ c: 'x-p', t: m[7] })
    else if (m[8]) toks.push({ c: 'x-text', t: m[8] })
  }
  return toks
}

/** A raw message with line numbers. XML is highlighted; FIN (MT) text is shown as it is. */
export function Code({ text, xml = true, className = '' }: { text: string; xml?: boolean; className?: string }) {
  return (
    <div className={`code overflow-auto py-2.5 ${className}`}>
      {text.split('\n').map((line, i) => (
        <div className="cl" key={i}>
          <span className="ln">{i + 1}</span>
          <span>
            {xml
              ? tokenize(line).map((t, j) => (
                  <Fragment key={j}>{t.c ? <span className={t.c}>{t.t}</span> : t.t}</Fragment>
                ))
              : line}
          </span>
        </div>
      ))}
    </div>
  )
}
