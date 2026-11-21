# lauz26
Lauzhack UBS and Kotlin challenge

## References

Research behind the main design choices of the app.

### Financial digital twin of a person
- Anshari, M., Almunawar, M. N., & Masri, M. (2022). *Digital Twin: Financial Technology's Next Frontier of Robo-Advisor.* Journal of Risk and Financial Management, 15(4), 163. [MDPI](https://mdpi.com/1911-8074/15/4/163)
  — Defines a digital twin as a digital representation of an individual that integrates their data and generates analytics, recommendations and alternatives for personal financial management.
- Kretschmer & Post (2026). *Financial Planning Apps: User Engagement and Learning Effects.* Netspar Industry Paper 2026-38. [PDF](https://www.netspar.nl/wp-content/uploads/Netspar-Industry-Paper-2026-38-WEB.pdf)
  — Pension planning app with a "digital twin" feature tested with 127 users, who showed high interest in it.
- The digital twin concept goes back to Michael Grieves (2002); see `docs/digital-twin-concepts.md` in the challenge materials.

### Talking to your future self
- Hershfield, H. E., Goldstein, D. G., Sharpe, W. F., Fox, J., Yeykelis, L., Carstensen, L. L., & Bailenson, J. N. (2011). *Increasing Saving Behavior Through Age-Progressed Renderings of the Future Self.* Journal of Marketing Research, 48(SPL), S23–S37. doi:10.1509/jmkr.48.SPL.S23. [PubMed](https://pubmed.ncbi.nlm.nih.gov/24634544/)
  — Seeing an age-progressed version of yourself increased (hypothetical) saving; in one study people were willing to save about twice as much. Psychological basis for the future-self chat.
- Pataranutaporn, P., et al. (2024). *Future You: A Conversation with an AI-Generated Future Self Reduces Anxiety, Negative Emotions, and Increases Future Self-Continuity.* IEEE Frontiers in Education Conference (FIE 2024). [arXiv:2405.12514](https://www.arxiv.org/pdf/2405.12514v2) · [MIT Media Lab](https://www.media.mit.edu/publications/future-you-a-conversation-with-an-ai-generated-future-self-reduces-anxiety-negative-emotions-and-increases-future-self-continuity/)
  — LLM chatbot playing the user's future self (344 participants): lower anxiety, higher future-self continuity. Closest prior work to our chat; we ground it in numbers computed by the engine instead of a generated backstory.

### Life-simulation games for financial decisions
- *Moonshot* study (2025). Education Sciences, 15(2), 227. [MDPI](https://mdpi.com/2227-7102/15/2/227)
  — Quasi-experiment with 293 students; game design variants made no difference between groups. Notes the lack of evidence on how to design financial-literacy games.
- Jones (2014). *Pecunia*, a life simulation where an 18-year-old character makes financial decisions and sees the consequences. [RPTEL](https://rptel.apsce.net/index.php/RPTEL/article/download/2014-09003/201)
  — Same "design your life" loop as ours; small pilot (16 participants).

### Modelling
- Keefer, D. L., & Bodily, S. E. (1983). *Three-Point Approximations for Continuous Random Variables.* Management Science, 29(5), 595–609. doi:10.1287/mnsc.29.5.595. [INFORMS](https://pubsonline.informs.org/doi/fpi/10.1287/mnsc.29.5.595)
  — Compares ways of representing a distribution with three points; percentile-based rules (Pearson–Tukey, Swanson) beat PERT and triangular ones. Basis for our pessimistic / neutral / optimistic cases and their probabilities (check the exact weights in the original paper before quoting them).
- Cocco, J. F., Gomes, F. J., & Maenhout, P. J. (2005). *Consumption and Portfolio Choice over the Life Cycle.* The Review of Financial Studies, 18(2), 491–533. doi:10.1093/rfs/hhi017. [IDEAS](https://ideas.repec.org/a/oup/rfinst/v18y2005i2p491-533.html)
  — Reference life-cycle model with risky labour income and borrowing constraints, solved numerically. The academic counterpart of our decision tree.

### What is new in this project
Prior work covers each piece separately. We combine a person's financial digital twin, a "design your life" game with
calculated and explainable consequences, and a future-self chat grounded in those numbers.
