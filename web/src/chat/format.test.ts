import { describe, expect, it } from 'vitest';
import { citationMarkdown, copyAnswer } from './format';
import type { Citation } from './types';

const one = 'K0123456789abcdef0123456789abcdef_E1';
const two = 'K1123456789abcdef0123456789abcdef_E1';
const sources = [{ evidenceId: one, documentName: '备份说明' }, { evidenceId: two, documentName: '恢复说明' }] as Citation[];

describe('answer source presentation', () => {
  it('keeps two local E1 sources distinct and never links an unreturned reference or code sample', () => {
    const output = citationMarkdown(`备份 [${one}]，恢复 [${two}]，未知 [Kunknown_E1]。\n\n\`[${one}]\`\n\n\`\`\`text\n[${two}]\n\`\`\``, sources);
    expect(output).toContain(`[1](#source-${one})`);
    expect(output).toContain(`[2](#source-${two})`);
    expect(output).toContain('[Kunknown_E1]');
    expect(output).not.toContain('#source-Kunknown');
    expect(output).toContain(`\`[${one}]\``);
    expect(output).toContain(`\`\`\`text\n[${two}]\n\`\`\``);
  });
  it('copies readable reference numbers with the actual source mapping', () => {
    expect(copyAnswer(`备份 [${one}]，恢复 [${two}]。`, sources)).toBe('备份 [1]，恢复 [2]。\n\n来源：\n[1] 备份说明\n[2] 恢复说明');
  });
});
