import { host } from './host'

/** Adapter for the RSS service's XMLParser contract. The platform pull parser
 * produces the entry/title/attribute shape the shared feed mapper consumes. */
export class XMLParser {
  parse(xml: string): unknown {
    return host('parseFeedXml', xml)
  }
}
