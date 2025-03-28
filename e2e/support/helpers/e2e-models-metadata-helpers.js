import {
  interceptIfNotPreviouslyDefined,
  popover,
  tableInteractive,
} from "e2e/support/helpers";

export function datasetEditBar() {
  return cy.findByTestId("dataset-edit-bar");
}

export function saveMetadataChanges() {
  interceptIfNotPreviouslyDefined({
    method: "POST",
    url: "/api/dataset",
    alias: "dataset",
  });

  cy.intercept("PUT", "/api/card/*").as("updateModelMetadata");
  cy.findByTestId("dataset-edit-bar").button("Save changes").click();
  cy.wait("@updateModelMetadata");
  cy.findByTestId("dataset-edit-bar").should("not.exist");

  cy.wait("@dataset");
}

export function openColumnOptions(column) {
  const columnNameRegex = new RegExp(`^${column}$`);

  tableInteractive()
    .findAllByTestId("header-cell")
    .contains(columnNameRegex)
    .scrollIntoView()
    .should("be.visible");

  // Query element again to ensure it's not unmounted
  tableInteractive()
    .findAllByTestId("header-cell")
    .contains(columnNameRegex)
    .click();
}

export function renameColumn(oldName, newName) {
  cy.findByDisplayValue(oldName).clear().type(newName).blur();
}

export function setColumnType(oldType, newType) {
  cy.findByTestId("sidebar-right")
    .findAllByTestId("select-button")
    .contains(oldType)
    .click();

  popover().within(() => {
    cy.findByText(oldType).closest(".ReactVirtualized__Grid").scrollTo(0, 0); // HACK: scroll to the top of the list. Ideally we should probably disable AccordionList virtualization
    cy.findByPlaceholderText("Search for a special type").realType(newType);
    cy.findByLabelText(newType).click();
  });
}

export function mapColumnTo({ table, column } = {}) {
  cy.findByText("Database column this maps to")
    .parent()
    .findByTestId("select-button")
    .click({ force: true });

  popover().contains(table).click();

  popover().contains(column).click();
}

export function setModelMetadata(modelId, callback) {
  return cy.request("GET", `/api/card/${modelId}`).then(response => {
    const { result_metadata } = response.body;
    return cy.request("PUT", `/api/card/${modelId}`, {
      result_metadata: result_metadata.map(callback),
    });
  });
}
