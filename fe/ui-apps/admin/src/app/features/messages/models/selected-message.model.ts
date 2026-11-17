import { Message } from './message.model';

export interface SelectedMessageEntry {
  readonly id: string;
  readonly message: Message;
}
