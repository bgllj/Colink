export interface IssueOut {
  code: string;
  message: string;
  severity: string;
  sheet?: string | null;
  coordinate?: string | null;
  line_index?: number | null;
}

export interface ImportIngestResponse {
  import_id: string;
  status: string;
  max_week?: number | null;
  duplicate_of?: string | null;
  issues: IssueOut[];
}

export interface ImportRowOut {
  row_id: string;
  status: string;
  selected: boolean;
  course_code?: string | null;
  course_name?: string | null;
  weekday?: number | null;
  period_start?: number | null;
  period_end?: number | null;
  week_text?: string | null;
  week_ranges: Array<Record<string, unknown>>;
  room_text?: string | null;
  sheet?: string | null;
  coordinate?: string | null;
  line_index?: number | null;
  raw_line?: string | null;
  issues: IssueOut[];
}

export interface ImportPreviewOut {
  import_id: string;
  status: string;
  max_week?: number | null;
  issues: IssueOut[];
  meta: Record<string, unknown>;
  rows: ImportRowOut[];
}

export interface ConfirmOut {
  import_id: string;
  status: string;
  class_id?: string | null;
  rows_confirmed: number;
  issues: IssueOut[];
}

export interface ConfirmRequest {
  row_ids?: string[] | null;
  class_id?: string | null;
  class_name?: string | null;
  replace?: boolean;
}

export interface ClassOut {
  id: string;
  name: string;
  grade?: string | null;
  major?: string | null;
  department?: string | null;
}

export interface MeetingOut {
  meeting_id: number;
  weekday: number;
  period_start?: number | null;
  period_end?: number | null;
  week_text?: string | null;
  weeks: number[];
  room_text?: string | null;
}

export interface ScheduleCourseOut {
  course_id: number;
  course_code: string;
  name: string;
  meetings: MeetingOut[];
}

export interface ScheduleSemesterOut {
  start_date?: string | null;
  max_week?: number | null;
  academic_year?: string | null;
  semester_name?: string | null;
}

export interface ScheduleOut {
  class_id: string;
  class_name: string;
  courses: ScheduleCourseOut[];
  semester: ScheduleSemesterOut;
}

export interface LoginResponse {
  access_token: string;
  token_type: string;
}

export interface AdminUser {
  id: string;
  username: string;
}
