import type { BasePromulgation } from '../../models/message.model';

// The audio promulgation: a longer text for reading out on the radio, sent to
// the radio station by e-mail. Its shape is Twitter's (one `text` field,
// rendered as plain text); the backend's counterpart is
// org.niord.core.promulgation.vo.AudioMessagePromulgationVo.
export interface AudioPromulgation extends BasePromulgation {
  readonly text?: string;
}
