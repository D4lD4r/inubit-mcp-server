# Open minor findings (for the cleanup pass before the PR)

- T002: three fixture READMEs (`admin-model-node-by-model-central-backend`, `-sd-ext-system-2`, `-managed-module`) have the probe paragraph inserted mid-sentence.
- T005: `ExternalSystems.java:65` takes a truncated `Module` entry as a linked name (and line 46 a truncated `ResourceType`); skip it with a warning like a masked one.
