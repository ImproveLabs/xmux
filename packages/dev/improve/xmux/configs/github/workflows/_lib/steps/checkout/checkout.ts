import { Step } from "@github-actions-workflow-ts/lib";

export const checkoutStep = new Step({
  name: "Checkout",
  uses: "actions/checkout@v5",
});
