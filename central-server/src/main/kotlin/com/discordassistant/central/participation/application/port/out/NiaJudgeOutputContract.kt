package com.discordassistant.central.participation.application.port.out

object NiaJudgeOutputContract {
    const val FORMAT_NAME: String = "nia_participation_judge_output_v1"

    val JSON_SCHEMA: String =
        """
        {
          "type": "object",
          "properties": {
            "schema": {
              "type": "string",
              "enum": ["${NiaJudgeLlmRequest.OUTPUT_SCHEMA}"]
            },
            "action": {
              "type": "string",
              "enum": ["IGNORE", "WAIT", "REACT", "SPEAK", "CANCEL"]
            },
            "reason": {
              "type": "string"
            },
            "reasonCode": {
              "type": ["string", "null"]
            },
            "evidenceRefs": {
              "type": ["array", "null"],
              "items": {
                "type": "string"
              }
            },
            "reactionCode": {
              "type": ["string", "null"]
            },
            "speechIntent": {
              "type": ["object", "null"],
              "properties": {
                "intentSummary": {
                  "type": "string"
                },
                "sceneDirection": {
                  "type": "string"
                },
                "actHint": {
                  "type": ["string", "null"]
                },
                "bubbleCount": {
                  "type": ["integer", "null"]
                },
                "maxBubbleChars": {
                  "type": ["integer", "null"]
                },
                "interactionReading": {
                  "type": ["string", "null"]
                },
                "informationDepth": {
                  "type": ["string", "null"]
                },
                "continuityRefs": {
                  "type": ["array", "null"],
                  "items": {
                    "type": "string"
                  }
                },
                "responseTargetRef": {
                  "type": "string"
                },
                "responseObligation": {
                  "type": "string",
                  "enum": ["REQUIRED", "OPTIONAL"]
                },
                "groundingNeed": {
                  "type": "string",
                  "enum": ["NONE", "WEB_VERIFY"]
                },
                "deliveryMode": {
                  "type": "string",
                  "enum": ["CHANNEL", "REPLY"]
                }
              },
              "required": [
                "intentSummary",
                "sceneDirection",
                "actHint",
                "bubbleCount",
                "maxBubbleChars",
                "interactionReading",
                "informationDepth",
                "continuityRefs",
                "responseTargetRef",
                "responseObligation",
                "groundingNeed",
                "deliveryMode"
              ],
              "additionalProperties": false
            },
            "toneAxes": {
              "type": ["object", "null"],
              "properties": {
                "warmth": {
                  "type": ["number", "null"]
                },
                "playfulness": {
                  "type": ["number", "null"]
                },
                "directness": {
                  "type": ["number", "null"]
                },
                "emotionalIntensity": {
                  "type": ["number", "null"]
                }
              },
              "required": ["warmth", "playfulness", "directness", "emotionalIntensity"],
              "additionalProperties": false
            },
            "confidence": {
              "type": "number"
            },
            "riskFlags": {
              "type": ["array", "null"],
              "items": {
                "type": "string"
              }
            },
            "reevaluateAfterMs": {
              "type": ["integer", "null"]
            },
            "beliefUpdates": {
              "type": ["object", "null"],
              "properties": {
                "commonGround": {
                  "type": ["array", "null"],
                  "items": {
                    "type": "object",
                    "properties": {
                      "code": {
                        "type": "string"
                      },
                      "confidence": {
                        "type": "number"
                      },
                      "evidenceRefs": {
                        "type": "array",
                        "items": {
                          "type": "string"
                        }
                      },
                      "status": {
                        "type": ["string", "null"],
                        "enum": ["ACTIVE", "SUPERSEDED", "REJECTED", null]
                      }
                    },
                    "required": ["code", "confidence", "evidenceRefs", "status"],
                    "additionalProperties": false
                  }
                },
                "intentHypotheses": {
                  "type": ["array", "null"],
                  "items": {
                    "type": "object",
                    "properties": {
                      "participantRef": {
                        "type": "string"
                      },
                      "code": {
                        "type": "string"
                      },
                      "probability": {
                        "type": "number"
                      },
                      "evidenceRefs": {
                        "type": "array",
                        "items": {
                          "type": "string"
                        }
                      },
                      "status": {
                        "type": ["string", "null"],
                        "enum": ["ACTIVE", "SUPERSEDED", "REJECTED", null]
                      }
                    },
                    "required": ["participantRef", "code", "probability", "evidenceRefs", "status"],
                    "additionalProperties": false
                  }
                },
                "commitments": {
                  "type": ["array", "null"],
                  "items": {
                    "type": "object",
                    "properties": {
                      "commitmentRef": {
                        "type": "string"
                      },
                      "topic": {
                        "type": "string"
                      },
                      "socialAct": {
                        "type": "string",
                        "enum": [
                          "REPLY",
                          "FIND_INFORMATION",
                          "FOLLOW_UP",
                          "APOLOGIZE",
                          "TELL_STORY",
                          "EXPLAIN",
                          "ANSWER"
                        ]
                      },
                      "evidenceRefs": {
                        "type": "array",
                        "items": {
                          "type": "string"
                        }
                      },
                      "confidence": {
                        "type": "number"
                      },
                      "status": {
                        "type": "string",
                        "enum": ["ACTIVE", "COMPLETED", "REJECTED"]
                      }
                    },
                    "required": [
                      "commitmentRef",
                      "topic",
                      "socialAct",
                      "evidenceRefs",
                      "confidence",
                      "status"
                    ],
                    "additionalProperties": false
                  }
                }
              },
              "required": ["commonGround", "intentHypotheses", "commitments"],
              "additionalProperties": false
            }
          },
          "required": [
            "schema",
            "action",
            "reason",
            "reasonCode",
            "evidenceRefs",
            "reactionCode",
            "speechIntent",
            "toneAxes",
            "confidence",
            "riskFlags",
            "reevaluateAfterMs",
            "beliefUpdates"
          ],
          "additionalProperties": false
        }
        """.trimIndent()
}
