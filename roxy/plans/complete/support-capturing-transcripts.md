# support-capturing-transcripts

## Goal
Support capturing a transcript of the conversation between the user and the agent. The transcript is a Markdown file with timestamps, saved in `.roxy/transcripts/transcript_[unix timestamp].md`. A checkbox in the UI allows toggling this feature.

## Proposed Changes
- RoxyProjectService.java: Add getTranscriptsDir() and ensure the directory exists.
- TranscriptService.java (New): Service to manage transcript capture, file I/O, and Markdown formatting.
- GenAIService.java: Inject TranscriptService and log user messages, model responses, and tool interactions.
- ChatView.xml: Add a check-box for 'Capture Transcript' in the UI.
- ChatView.java: Bind the checkbox to the TranscriptService.

## Implementation Steps
- [ ] Step 1: Update RoxyProjectService to handle the transcripts directory.
- [ ] Step 2: Implement TranscriptService to manage file creation and logging.
- [ ] Step 3: Integrate TranscriptService into GenAIService to capture communications.
- [ ] Step 4: Add the 'Capture Transcript' checkbox to ChatView.xml.
- [ ] Step 5: Update ChatView.java to handle checkbox interactions and initialize state.
- [ ] Step 6: Verify the feature by running the application and checking generated transcripts.

## Implementation Progress
- [x] Step 1: Update RoxyProjectService to handle the transcripts directory. [DONE]
- [x] Step 2: Implement TranscriptService to manage file creation and logging. [DONE]
- [x] Step 3: Integrate TranscriptService into GenAIService to capture communications. [DONE]
- [x] Step 4: Add the 'Capture Transcript' checkbox to ChatView.xml. [DONE]
- [x] Step 5: Update ChatView.java to handle checkbox interactions and initialize state. [DONE]
- [x] Step 6: Verify the feature by running the application and checking generated transcripts. [DONE]

## Agent Context

